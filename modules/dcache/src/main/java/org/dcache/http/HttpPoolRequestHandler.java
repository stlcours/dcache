package org.dcache.http;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import diskCacheV111.util.HttpByteRange;
import diskCacheV111.util.TimeoutCacheException;
import dmg.util.HttpException;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

/**
 * HttpPoolRequestHandler - handle HTTP client - server communication and pass
 * on commands received from the client to the HTTP-mover.
 *
 * Similarly, write back responses received from the mover to the client.
 *
 * @author tzangerl
 *
 */
public class HttpPoolRequestHandler extends HttpRequestHandler
{

    private final static Logger _logger =
        LoggerFactory.getLogger(HttpPoolRequestHandler.class);

    /** the movers that were accessed - will be used to inform the movers that
     * they don't need to wait for further messages for clients that send the
     * "Connection: close" header.
     */
    private final Set<HttpProtocol_2> _movers;
    /**
     * The server in the context of which this handler is executed
     */
    HttpPoolNettyServer _executionServer;

    public HttpPoolRequestHandler(HttpPoolNettyServer executionServer) {
        _executionServer = executionServer;
        _movers = new HashSet<HttpProtocol_2>();
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx,
                              ChannelStateEvent event) {

        _logger.debug("Called channelClosed.");
        for (HttpProtocol_2 mover : _movers) {
            mover.close(this);
        }

        _movers.clear();
    }

    @Override
    public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent event)
        throws Exception {

        if (event.getState() == IdleState.ALL_IDLE) {

            if (_logger.isInfoEnabled()) {
                long idleTime = System.currentTimeMillis() -
                    event.getLastActivityTimeMillis();
                _logger.info("Closing idling connection without opened files." +
                          " Connection has been idle for {} ms.", idleTime);
            }

            ctx.getChannel().close();
        }

    }

    /**
     * Single GET operation. Find the correct mover using the UUID in the GET
     * and access the file via the mover. Range queries are supported. The
     * resulting file will be sent to the remote peer in chunks to avoid
     * server side memory issues.
     *
     */
    @Override
    protected void doOnGet(ChannelHandlerContext context,
                           MessageEvent event,
                           HttpRequest request) {

        ChannelFuture future = null;
        HttpProtocol_2 mover = null;

        try {

            mover = getMoverForRequest(request);
            _movers.add(mover);

            mover.open(this);

            ChunkedInput responseContent;
            long fileSize = mover.getFileSize();

            List<HttpByteRange> ranges = parseHttpRange(request,
                                                 0,
                                                 fileSize-1);

            URI path = new URI(request.getUri());

            if (ranges == null || ranges.isEmpty()) {
                /*
                 * GET for a whole file
                 */
                responseContent = mover.read(path);
                future = sendHTTPFullHeader(context, event, fileSize,
                        mover.getFileName());
                future = event.getChannel().write(responseContent);
            } else if( ranges.size() == 1){
                /*
                 * GET for a single range
                 *
                 * rfc2616 is not strong enough about using multi-range reply
                 * for a single range:
                 *
                 *    The multipart/byteranges media type includes two or more parts,
                 *    each with its own Content-Type and Content-Range fields. The
                 *    required boundary parameter specifies the boundary string used
                 *    to separate each body-part.
                 *
                 * To keep other'readings' happy, do not send multi-range reply on
                 * single range request.
                 */
                HttpByteRange range = ranges.get(0);
                future = sendHTTPPartialHeader(context, event,
                    range.getLower(),
                    range.getUpper(),
                    fileSize);
                responseContent = mover.read(path,
                        range.getLower(),
                        range.getUpper());
                future = event.getChannel().write(responseContent);
            } else {
                /*
                 * GET for multiple ranges
                 */
                future = sendHTTPMultipartHeader(context, event);
                for(HttpByteRange range: ranges) {
                    responseContent = mover.read(path,
                            range.getLower(),
                            range.getUpper());
                    sendHTTPMultipartFragment(context,
                            event,
                            range.getLower(),
                            range.getUpper(),
                            fileSize);
                    event.getChannel().write(responseContent);
                }
                future = sendHTTPMultipartEnd(context, event);
            }

        } catch (HttpException e) {
            if (future == null) {
                future = sendHTTPError(context, HttpResponseStatus.valueOf(e.getErrorCode()), e.getMessage());
            } else {
                _logger.warn("Failure in HTTP GET: {}", e.getMessage());
                event.getChannel().close();
            }
        } catch (TimeoutCacheException e) {
            if (future == null) {
                future = sendHTTPError(context,
                                   REQUEST_TIMEOUT,
                                   e.getMessage());
            } else {
                _logger.warn("Failure in HTTP GET: {}", e.getMessage());
                event.getChannel().close();
            }
            _movers.remove(mover);
        } catch (IllegalArgumentException e) {
            if (future == null) {
                future = sendHTTPError(context, BAD_REQUEST, e.getMessage());
            } else {
                _logger.warn("Failure in HTTP GET: {}", e.getMessage());
                event.getChannel().close();
            }
        } catch (IOException e) {
            if (future == null) {
                future = sendHTTPError(context,
                        INTERNAL_SERVER_ERROR,
                        e.getMessage());
            } else {
                _logger.warn("Failure in HTTP GET: {}", e.getMessage());
                event.getChannel().close();
            }
        } catch (RuntimeException e) {
            if (future == null) {
                future = sendHTTPError(context,
                        INTERNAL_SERVER_ERROR,
                        e.getMessage());
            } else {
                _logger.warn("Failure in HTTP GET: {}", e.getMessage());
                event.getChannel().close();
            }
        } catch (URISyntaxException e) {
            if (future == null) {
                future = sendHTTPError(context, BAD_REQUEST,
                        "URI not valid: " + e.getMessage());
            } else {
                _logger.warn("Failure in HTTP GET: {}", e.getMessage());
                event.getChannel().close();
            }
        } finally {

            if (!isKeepAlive() && future != null) {
                    future.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    /**
     * Get the mover for a certain HTTP request. The movers are identified by
     * UUIDs generated upon mover start and sent back to the door as a part
     * of the address info.
     *
     *
     * @param request HttpRequest that was sent by the client
     * @return HTTP-mover for specified UUID
     * @throws IllegalArgumentException Request did not include UUID or no
     *         mover found for UUID in the request
     */
    private HttpProtocol_2 getMoverForRequest(HttpRequest request)
        throws IllegalArgumentException {
        QueryStringDecoder queryStringDecoder =
            new QueryStringDecoder(request.getUri());

        Map<String, List<String>> params = queryStringDecoder.getParameters();

        if (!params.containsKey(HttpProtocol_2.UUID_QUERY_PARAM)) {
            if(!request.getUri().equals("/favicon.ico")) {
                _logger.error("Received request without UUID in the query " +
                        "string. Request-URI was {}", request.getUri());
            }

            throw new IllegalArgumentException("Query string does not include any UUID.");
        }

        List<String> uuidList = params.get(HttpProtocol_2.UUID_QUERY_PARAM);

        if (uuidList.size() < 1) {
            throw new IllegalArgumentException("UUID parameter does not include any value.");
        }

        UUID uuid = UUID.fromString(uuidList.get(0));
        HttpProtocol_2 mover = _executionServer.getMover(uuid);

        if (mover == null) {
            throw new IllegalArgumentException("Mover for UUID " + uuid + " timed out. " +
                                               "Please send new request to door.");
        }

        return mover;
    }
}

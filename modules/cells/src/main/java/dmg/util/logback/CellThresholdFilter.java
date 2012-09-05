package dmg.util.logback;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import dmg.cells.nucleus.CellNucleus;
import dmg.cells.nucleus.CDC;

import ch.qos.logback.core.Appender;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Marker;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;
import java.util.Iterator;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class CellThresholdFilter extends TurboFilter
{
    private FilterReply _onHigherOrEqual = FilterReply.NEUTRAL;
    private FilterReply _onLower = FilterReply.DENY;

    private final List<Threshold> _thresholds = Lists.newArrayList();

    /**
     * Adds a default threshold that will be used by all filters
     * unless overridden.
     */
    public void addThreshold(Threshold threshold)
    {
        checkState(!isStarted(), "Cannot add threshold after start");
        _thresholds.add(threshold);
    }

    /**
     * Get the FilterReply when the level of the logging request is
     * higher or equal to the effective threshold.
     *
     * @return FilterReply
     */
    public FilterReply getOnHigherOrEqual()
    {
        return _onHigherOrEqual;
    }

    public void setOnHigherOrEqual(FilterReply onHigherOrEqual)
    {
        checkNotNull(onHigherOrEqual);
        _onHigherOrEqual = onHigherOrEqual;
    }

    /**
     * Get the FilterReply when the level of the logging request is
     * lower than the effective threshold.
     *
     * @return FilterReply
     */
    public FilterReply getOnLower()
    {
        return _onLower;
    }

    public void setOnLower(FilterReply onLower)
    {
        checkNotNull(onLower);
        _onLower = onLower;
    }

    private Set<Appender<ILoggingEvent>>
        getAppenders(LoggerContext context)
    {
        Set<Appender<ILoggingEvent>> appenders = Sets.newHashSet();
        for (Logger logger: context.getLoggerList()) {
            Iterator<Appender<ILoggingEvent>> i = logger.iteratorForAppenders();
            while (i.hasNext()) {
                Appender<ILoggingEvent> appender = i.next();
                appenders.add(appender);
            }
        }
        return appenders;
    }

    @Override
    public void start()
    {
        LoggerContext context = (LoggerContext) getContext();

        for (Appender<ILoggingEvent> appender: getAppenders(context)) {
            String appenderName = appender.getName();

            RootFilterThresholds.addAppender(appenderName);
            for (Threshold threshold: _thresholds) {
                if (threshold.isApplicableToAppender(appender)) {
                    RootFilterThresholds.setThreshold(
                            threshold.getLogger(),
                            appenderName,
                            threshold.getLevel());
                }
            }

            CellThresholdFilterCompanion filter =
                new CellThresholdFilterCompanion(appenderName);
            filter.start();
            appender.addFilter(filter);
        }

        super.start();
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t)
    {
        if (!isStarted()) {
            return FilterReply.NEUTRAL;
        }

        String cell = MDC.get(CDC.MDC_CELL);
        CellNucleus nucleus = CellNucleus.getLogTargetForCell(cell);
        if (nucleus == null) {
            return FilterReply.NEUTRAL;
        }

        FilterThresholds thresholds = nucleus.getLoggingThresholds();
        if (thresholds == null) {
            return FilterReply.NEUTRAL;
        }

        Level threshold =
            thresholds.getThreshold(LoggerName.getInstance(logger));
        if (threshold == null) {
            return FilterReply.NEUTRAL;
        }

        if (level.isGreaterOrEqual(threshold)) {
            return _onHigherOrEqual;
        } else {
            return _onLower;
        }
    }
}
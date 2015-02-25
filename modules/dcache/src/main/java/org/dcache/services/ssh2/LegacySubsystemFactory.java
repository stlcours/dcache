package org.dcache.services.ssh2;

import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.server.Command;
import org.springframework.beans.factory.annotation.Required;

import java.io.File;

import dmg.cells.nucleus.CellEndpoint;
import dmg.cells.nucleus.CellMessageSender;

public class LegacySubsystemFactory implements NamedFactory<Command>, CellMessageSender
{
    private CellEndpoint _endpoint;

    private File _historyFile;
    private boolean _useColor;

    @Required
    public void setHistoryFile(File historyFile)
    {
        _historyFile = historyFile;
    }

    @Required
    public void setUseColor(boolean useColor)
    {
        _useColor = useColor;
    }

    @Override
    public void setCellEndpoint(CellEndpoint endpoint) {
        _endpoint = endpoint;
    }

    @Override
    public String getName()
    {
        return "legacy";
    }

    @Override
    public Command create()
    {
        return new LegacyAdminShellCommand(_endpoint, _historyFile, _useColor);
    }
}

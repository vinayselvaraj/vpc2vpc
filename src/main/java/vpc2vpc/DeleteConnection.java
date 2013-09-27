package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

/**
 * DeleteConnection
 *
 * @author Vinay Selvaraj
 */
public class DeleteConnection extends BaseAction {

  private Logger LOG = Logger.getLogger(DeleteConnection.class);

  public DeleteConnection(String[] args, AWSCredentials awsCreds) {
    super(args, awsCreds);
  }

  public void run() {

    Options options = new Options();
    options.addOption("h", "help", false, "display the help message");
    options.addOption("i", "vpc2vpcId", true, "ID of the vpc2vpc connection to be deleted");
    options.addOption("v", "verbose", false, "be extra verbose");

    CommandLineParser parser = new PosixParser();
    CommandLine cmd = null;

    try {
      cmd = parser.parse(options, args);
    } catch (ParseException pe) {
      LOG.error("Unable to parse command: " + pe.getMessage());
      System.exit(1);
    }

    String vpc2vpcId = null;
    if (cmd != null) {
      if (cmd.hasOption("v")) {
        LogManager.getRootLogger().setLevel(Level.DEBUG);
      }

      vpc2vpcId = cmd.getOptionValue("i");
    }

    if (vpc2vpcId == null || cmd == null || cmd.hasOption("h")) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("vpc2vpc delete [options]", options);
      System.exit(0);
    }
    
    HashMap<String, VPC2VPCConnection> vpc2vpcIdConnections = VPC2VPCHelper.getInstance().getVPC2VPCConnections(awsCreds);
    VPC2VPCConnection vpc2vpcConnection = vpc2vpcIdConnections.get(vpc2vpcId);
    
    if(vpc2vpcConnection == null) {
      System.out.printf("ERROR: Unable to find a vpc2vpc connection with the ID: %s\n", vpc2vpcId);
      System.exit(1);
    }
    
    HashMap<String, VPNEndpoint> vpcIdVpnEndpoint = vpc2vpcConnection.getVpcIdVpnEndpoint();
    List<VPNEndpoint> vpnEndpoints = new ArrayList();
    vpnEndpoints.addAll(vpcIdVpnEndpoint.values());
        
    if(vpnEndpoints.size() > 0) {
      LOG.debug("Starting the rollback");
      RollbackHelper.getInstance().rollback(awsCreds, vpnEndpoints, true);
      LOG.info("The vpc2vpc connection has been deleted");
    }
    
  }
}

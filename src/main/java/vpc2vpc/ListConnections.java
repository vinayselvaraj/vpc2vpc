package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.Vpc;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

/**
 * List Connections
 *
 * @author Vinay Selvaraj
 */
public class ListConnections extends BaseAction {

  private Logger LOG = Logger.getLogger(ListConnections.class);

  public ListConnections(String[] args, AWSCredentials awsCreds) {
    super(args, awsCreds);
  }

  public void run() {

    Options options = new Options();
    options.addOption("h", "help", false, "display the help message");
    options.addOption("v", "verbose", false, "be extra verbose");

    CommandLineParser parser = new PosixParser();
    CommandLine cmd = null;

    try {
      cmd = parser.parse(options, args);
    } catch (ParseException pe) {
      LOG.error("Unable to parse command: " + pe.getMessage());
      System.exit(1);
    }

    if (cmd != null) {
      if (cmd.hasOption("v")) {
        LogManager.getRootLogger().setLevel(Level.DEBUG);
      }
    }

    HashMap<String, VPC2VPCConnection> vpc2vpcIdConnections = VPC2VPCHelper.getInstance().getVPC2VPCConnections(awsCreds);

    for(String vpc2vpcId : vpc2vpcIdConnections.keySet()) {
      
      VPC2VPCConnection vpc2vpcConnection = vpc2vpcIdConnections.get(vpc2vpcId);
      System.out.printf("%s : ", vpc2vpcId);
      HashMap<String, VPNEndpoint> vpcIdVpnEndpoint = vpc2vpcConnection.getVpcIdVpnEndpoint();
      int index = 0;
      for(String vpcId : vpcIdVpnEndpoint.keySet()) {
        VPNEndpoint vpnEndpoint = vpcIdVpnEndpoint.get(vpcId);
        System.out.printf("%s/%s(%s)", vpnEndpoint.getVpc().getCidrBlock(), vpnEndpoint.getVpc().getVpcId(), vpnEndpoint.getRegion().getRegionName());
        index = index + 1;
        if(index < vpcIdVpnEndpoint.keySet().size()) {
          System.out.printf(" <==> ");
        } else {
          System.out.printf(", ");
        }
      }
      System.out.printf("%s\n", vpc2vpcConnection.getCreatedOn());
    }
  }
  
  
}

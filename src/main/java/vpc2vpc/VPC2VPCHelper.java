package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.Address;
import com.amazonaws.services.ec2.model.DescribeAddressesResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.Vpc;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import org.apache.log4j.Logger;

/**
 * VPC2VPCHelper
 *
 * @author Vinay Selvaraj
 */
public class VPC2VPCHelper {

  private Logger LOG = Logger.getLogger(VPC2VPCHelper.class);
  private static VPC2VPCHelper instance;

  private VPC2VPCHelper() {
  }

  public static VPC2VPCHelper getInstance() {
    if (instance == null) {
      instance = new VPC2VPCHelper();
    }
    return instance;
  }

  public HashMap<String, VPC2VPCConnection> getVPC2VPCConnections(AWSCredentials awsCreds) {

    HashMap<Region, List> regionVpcs = VPCHelper.getInstance().listRegionVpcs(awsCreds);
    HashMap<Region, List> regionInstances = EC2Helper.getInstance().listRegionInstances(awsCreds);

    HashMap<String, Vpc> vpcIdVpc = new HashMap();
    for (Region region : regionVpcs.keySet()) {
      List<Vpc> vpcs = regionVpcs.get(region);
      for (Vpc vpc : vpcs) {
        vpcIdVpc.put(vpc.getVpcId(), vpc);
      }
    }

    HashMap<String, VPC2VPCConnection> vpc2vpcIdConnections = new HashMap();

    for (Region region : regionInstances.keySet()) {

      AmazonEC2Client ec2Client = new AmazonEC2Client(awsCreds);
      ec2Client.setEndpoint(region.getEndpoint());

      List<Instance> instances = regionInstances.get(region);
      for (Instance ec2Instance : instances) {

        LOG.debug("instance: " + instance);
        if (ec2Instance.getState().getName().equals("running")) {
          String vpcId = ec2Instance.getVpcId();
          List<Tag> tags = ec2Instance.getTags();

          HashMap<String, String> vpc2vpcTags = new HashMap();
          for (Tag tag : tags) {
            String key = tag.getKey();
            String value = tag.getValue();
            vpc2vpcTags.put(key, value);
          }

          String vpc2vpcId = vpc2vpcTags.get("vpc2vpc:id");
          String vpc2vpcCreatedOnStr = vpc2vpcTags.get("vpc2vpc:created_on");
          String vpc2vpcPublicIp = vpc2vpcTags.get("vpc2vpc:public_ip");

          if (vpc2vpcId != null) {
            VPC2VPCConnection connection = vpc2vpcIdConnections.get(vpc2vpcId);
            if (connection == null) {
              Date createdOn = null;
              if (vpc2vpcCreatedOnStr != null) {
                try {
                  createdOn = new Date(Long.parseLong(vpc2vpcCreatedOnStr));
                } catch (Exception e) {
                  // Eat it
                  LOG.debug("Ignoring exception caught while parsing date string: " + e.getMessage());
                }
              }
              connection = new VPC2VPCConnection(vpc2vpcId, createdOn);
              vpc2vpcIdConnections.put(vpc2vpcId, connection);
            }

            VPNEndpoint vpnEndpoint = new VPNEndpoint();
            vpnEndpoint.setRegion(region);
            vpnEndpoint.setVpc(vpcIdVpc.get(vpcId));
            vpnEndpoint.setInstance(ec2Instance);
            vpnEndpoint.setElasticIPAddress(vpc2vpcPublicIp);

            // Get the id of the security group
            ec2Client.describeSecurityGroups();
            DescribeSecurityGroupsResult descSecGrpResult = ec2Client.describeSecurityGroups();
            for(SecurityGroup sg : descSecGrpResult.getSecurityGroups()) {
              if(sg.getGroupName().equals(vpc2vpcId)) {
                vpnEndpoint.setSecurityGroupId(sg.getGroupId());
                break;
              }
            }
            
            // Get the EIP allocation ID
            DescribeAddressesResult descAddressesResult = ec2Client.describeAddresses();
            List<Address> addresses = descAddressesResult.getAddresses();
            for(Address address : addresses) {
              if(address.getPublicIp().equals(vpc2vpcPublicIp)) {
                vpnEndpoint.setElasticIPAddress(address.getPublicIp());
                vpnEndpoint.setElasticIPAllocationId(address.getAllocationId());

                LOG.debug("Found EIP: " + address);
                break;
              }
            }

            HashMap<String, VPNEndpoint> vpcIdVpnEndpoint = connection.getVpcIdVpnEndpoint();
            vpcIdVpnEndpoint.put(vpcId, vpnEndpoint);
          }
        }
      }
    }

    return vpc2vpcIdConnections;
  }
}

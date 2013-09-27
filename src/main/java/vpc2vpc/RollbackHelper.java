package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DeleteRouteRequest;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.ReleaseAddressRequest;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.Route;
import com.amazonaws.services.ec2.model.RouteTable;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import java.util.List;
import org.apache.log4j.Logger;

/**
 * RollbackHelper
 *
 * @author Vinay Selvaraj
 */
public class RollbackHelper {

  private Logger LOG = Logger.getLogger(RollbackHelper.class);
  private static RollbackHelper instance;

  private RollbackHelper() {
  }

  public static RollbackHelper getInstance() {
    if (instance == null) {
      instance = new RollbackHelper();
    }
    return instance;
  }

  public void rollback(AWSCredentials awsCreds, List<VPNEndpoint> vpnEndpoints, boolean showStatus) {
    AmazonEC2Client ec2Client = new AmazonEC2Client(awsCreds);

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      LOG.debug("Rolling back changes in " + vpnEndpoint.getRegion().getRegionName());

      // Remove Route Table entries that were created
      try {
        List<RouteTable> routeTables = ec2Client.describeRouteTables().getRouteTables();
        LOG.debug("Found " + routeTables.size() + " route tables in " + vpnEndpoint.getRegion().getRegionName());
        for (RouteTable routeTable : routeTables) {
          List<Route> routes = routeTable.getRoutes();
          for (Route route : routes) {
            LOG.debug("Checking if route is for vpc2vpc: " + route);

            String routeInstanceId = route.getInstanceId();
            if (routeInstanceId != null && routeInstanceId.equals(vpnEndpoint.getInstance().getInstanceId())) {
              DeleteRouteRequest deleteRouteRequest = new DeleteRouteRequest();
              deleteRouteRequest.setRouteTableId(routeTable.getRouteTableId());
              deleteRouteRequest.setDestinationCidrBlock(route.getDestinationCidrBlock());
              LOG.debug("About to delete route to " + route.getDestinationCidrBlock() + " from " + vpnEndpoint.getVpc());
              ec2Client.deleteRoute(deleteRouteRequest);
              LOG.debug("Deleted route");
            }
          }
        }

      } catch (Exception e) {
        LOG.debug("Caught exception during rollback while deleting route table: " + e.getMessage());
      }

      // Remove EC2 instance if one exists
      if (showStatus) {
        try {
          LOG.info(String.format("Deleting VPN instances in %s/%s (%s)",
                  vpnEndpoint.getVpc().getCidrBlock(),
                  vpnEndpoint.getVpc().getVpcId(),
                  vpnEndpoint.getRegion().getRegionName()));
        } catch (Exception e) {
          // Eat it
          LOG.debug("Ignoring exception: " + e.getMessage());
        }
      }
      try {
        if (vpnEndpoint.getInstance() != null) {
          Instance ec2Instance = vpnEndpoint.getInstance();
          TerminateInstancesRequest request = new TerminateInstancesRequest().withInstanceIds(ec2Instance.getInstanceId());
          ec2Client.terminateInstances(request);
          LOG.debug("Sent request to terminate EC2 instance: " + ec2Instance.getInstanceId());
        }
      } catch (Exception e) {
        LOG.debug("Caught exception during rollback while terminating EC2 instance: " + e.getMessage());
      }
    }

    if (showStatus) {
      LOG.info("Waiting on instances to terminate..");
    }

    int timeoutInMinutes = 10; // TODO: Remove hardcoding and put in config
    long startTime = System.currentTimeMillis();
    long endTime = startTime + (timeoutInMinutes * 60 * 1000);
    boolean waitDone = false;
    while (!waitDone && (System.currentTimeMillis() < endTime)) {

      waitDone = true;
      for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
        try {
          ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());
          String instanceId = vpnEndpoint.getInstance().getInstanceId();
          DescribeInstancesRequest descInstancesReq = new DescribeInstancesRequest().withInstanceIds(instanceId);
          List<Reservation> reservations = ec2Client.describeInstances(descInstancesReq).getReservations();
          Instance ec2InstanceBeingDeleted = null;
          if (reservations != null && reservations.size() > 0) {
            ec2InstanceBeingDeleted = reservations.get(0).getInstances().get(0);
            LOG.debug("Waiting on instances: " + ec2InstanceBeingDeleted.getInstanceId() + " state: " + ec2InstanceBeingDeleted.getState());
            if (!ec2InstanceBeingDeleted.getState().getName().equals("terminated")) {
              waitDone = false;
            }
          } else {
            LOG.debug("Unable to find the status on the instance: " + instanceId);
          }
        } catch (Exception e) {
          LOG.debug("Ignoring exception: " + e.getMessage());
        }
      }
      if (!waitDone) {
        try {
          Thread.sleep(15 * 1000);
        } catch (Exception e) {
          LOG.debug("Ignoring exception caught while sleeping");
          // eat it
        }
      }
    }

    // TODO: USE RETRIES INSTEAD OF THIS SLEEP!
    // This sleep is needed since sometimes the AWS API doesn't allow the 
    // SG / EIP to be removed since it still thinks it is in use by the 
    // instance(s) we just terminated
    try {
      Thread.sleep(15 * 1000);
    } catch (Exception e) {
      LOG.debug("Ignoring exception caught while sleeping");
      // eat it
    }

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      // Release Elastic / Public IPs if one exists
      try {
        if (vpnEndpoint.getElasticIPAddress() != null && vpnEndpoint.getElasticIPAllocationId() != null) {
          ReleaseAddressRequest request = new ReleaseAddressRequest().withAllocationId(vpnEndpoint.getElasticIPAllocationId());
          LOG.debug("About to release elastic IP: " + request);
          ec2Client.releaseAddress(request);
          LOG.debug("Released elastic IP: " + vpnEndpoint.getElasticIPAddress());
        } else {
          LOG.debug("Unable to find the EIP Address or EIP Allocation ID");
        }
      } catch (Exception e) {
        LOG.debug("Caught exception during rollback while releasing Elastic IPs: " + e.getMessage());
      }

      // Remove VPC Security Groups that were created
      try {
        DeleteSecurityGroupRequest deleteSecGrpReq = new DeleteSecurityGroupRequest();
        deleteSecGrpReq.setGroupId(vpnEndpoint.getSecurityGroupId());
        ec2Client.deleteSecurityGroup(deleteSecGrpReq);
        LOG.debug("Deleted security group: " + vpnEndpoint.getSecurityGroupId());
      } catch (Exception e) {
        LOG.debug("Caught exception during rollback while deleting security group: " + e.getMessage());
      }
    }
  }
}

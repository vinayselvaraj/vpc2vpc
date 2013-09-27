package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.model.AllocateAddressRequest;
import com.amazonaws.services.ec2.model.AllocateAddressResult;
import com.amazonaws.services.ec2.model.AssociateAddressRequest;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateRouteRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupResult;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeRouteTablesResult;
import com.amazonaws.services.ec2.model.DomainType;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.ModifyNetworkInterfaceAttributeRequest;
import com.amazonaws.services.ec2.model.NetworkInterface;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.Route;
import com.amazonaws.services.ec2.model.RouteTable;
import com.amazonaws.services.ec2.model.RouteTableAssociation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.Vpc;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

/**
 * CreateConnection
 *
 * @author Vinay Selvaraj
 */
public class CreateConnection extends BaseAction {

  private Logger LOG = Logger.getLogger(CreateConnection.class);
  private List<Vpc> vpcs;
  private List<Subnet> subnets;
  private HashMap<String, Vpc> vpcIdMap;
  private HashMap<String, List<Vpc>> vpcCidrMap;
  private HashMap<String, Subnet> subnetIdMap;
  private HashMap<String, List<Subnet>> subnetCidrMap;
  private HashMap<String, Region> vpcIdRegionMap;
  private HashMap<String, Region> subnetIdRegionMap;
  private HashMap<String, List<RouteTable>> vpcIdRouteTableMap;
  String vpc2vpcId = "vpc2vpc-" + UUID.randomUUID().toString().substring(0, 8);
  private final String CLOUD_INIT_TEMPLATE = "cloud-init.template";
  private final String CLOUD_INIT_IPSEC_CONF_TEMPLATE = "cloud-init-ipsec-conf.template";

  public CreateConnection(String[] args, AWSCredentials awsCreds) {
    super(args, awsCreds);
  }

  public void run() {

    Options options = new Options();

    options.addOption("h", "help", false, "display the help message");
    options.addOption("t", "instance-type", true, "instance type (t1.micro, m1.small, etc..)");
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

      if (cmd.hasOption("h")) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("vpc2vpc create [options] <endpoint1> <endpoint2> <endpointX>", options);
        System.exit(0);
      }
    }

    populateLookupData();

    List<String> endpointArgs = new ArrayList();
    for (String arg : args) {
      if (isCidr(arg) || isSubnetId(arg) || isVpcId(arg)) {
        endpointArgs.add(arg);
      }
    }

    List<VPNEndpoint> vpnEndpoints = new ArrayList();
    for (String endpointArg : endpointArgs) {
      VPNEndpoint endpoint = getVpnEndpoint(endpointArg);
      if (endpoint != null) {
        vpnEndpoints.add(endpoint);
      }
    }

    if (endpointArgs.size() != vpnEndpoints.size()) {
      LOG.error("Errors detected.  Aborting operation");
      System.exit(1);
    }

    if (areEndpointsDuplicate(vpnEndpoints)) {
      LOG.error("Two or more endpoints contain the same VPC ID or CIDR block.  Aborting operation");
      System.exit(1);
    }

    LOG.debug("Found " + vpnEndpoints.size() + " endpoints");
    for (VPNEndpoint endpoint : vpnEndpoints) {
      LOG.debug(endpoint);
      updateOrConfirmPublicSubnet(endpoint);
      LOG.debug(endpoint);

      // Abort if a public subnet is not set
      if (endpoint.getSubnet() == null) {
        LOG.error("Aborting operation");
        System.exit(1);
      }
    }

    // From this point on, we're going to be creating things
    LOG.info("Preparing to create vpc2vpc connection");

    try {

      // Check if routes exist
      if (checkIfRoutesExist(vpnEndpoints)) {
        throw new RuntimeException("One or more VPC to VPC routes already exist between the endpoints");
      }

      // Starting with the elastic IPs
      allocateElasticIPs(vpnEndpoints);
      LOG.debug("Allocated elastic IPs");

      // Configure Security Groups
      configureSecurityGroups(vpnEndpoints);
      LOG.debug("Configured security groups");

      // Launch the EC2 instances
      launchInstances(vpnEndpoints);
      LOG.debug("Launched vpc2vpc instances");

      // Wait for instances to start up
      waitOnInstances(vpnEndpoints);

      // Disable Src/Dest checks on instances
      disableSrcDestCheck(vpnEndpoints);

      // Create tags
      createTags(vpnEndpoints);

      // Associate Public IP
      associatePublicIP(vpnEndpoints);

      // Setup Routes
      createAndAssociateRoutes(vpnEndpoints);

      LOG.info("vpc2vpc connection (" + vpc2vpcId + ") has been created.  Please allow 15 minutes for VPN to start");

    } catch (Exception e) {
      LOG.error("Aborting operation: " + e.getMessage());
      RollbackHelper.getInstance().rollback(awsCreds, vpnEndpoints, false);
      System.exit(1);
    }

  }

  /**
   * Checks if routes between the selected VPCs already exist
   *
   * @param vpnEndpoints
   * @return
   */
  private boolean checkIfRoutesExist(List<VPNEndpoint> vpnEndpoints) {
    boolean routesExist = false;

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());
      DescribeRouteTablesResult descRouteTableResult = ec2Client.describeRouteTables();
      List<RouteTable> routeTables = descRouteTableResult.getRouteTables();

      for (RouteTable routeTable : routeTables) {
        if (routeTable.getVpcId().equals(vpnEndpoint.getVpc().getVpcId())) {
          List<Route> routes = routeTable.getRoutes();
          for (Route route : routes) {
            for (VPNEndpoint extVpnEndpoint : vpnEndpoints) {
              if (!vpnEndpoint.equals(extVpnEndpoint)) {
                LOG.debug("Checking if route allows requested traffic: " + route);
                if (route.getDestinationCidrBlock().endsWith(extVpnEndpoint.getVpc().getCidrBlock())) {
                  routesExist = true;
                  LOG.error("A route already exists between " + vpnEndpoint.getVpc().getCidrBlock() + " and " + extVpnEndpoint.getVpc().getCidrBlock());
                }
              }
            }
          }
        }
      }

    }

    return routesExist;
  }

  /**
   * Create routes
   *
   * @param vpnEndpoints
   */
  private void createAndAssociateRoutes(List<VPNEndpoint> vpnEndpoints) {

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      for (VPNEndpoint extVpnEndpoint : vpnEndpoints) {
        if (!vpnEndpoint.equals(extVpnEndpoint)) {

          // Get route tables
          DescribeRouteTablesResult descRouteTablesResult = ec2Client.describeRouteTables();
          List<RouteTable> routeTables = descRouteTablesResult.getRouteTables();
          for (RouteTable routeTable : routeTables) {
            if (routeTable.getVpcId().equals(vpnEndpoint.getVpc().getVpcId())) {
              // Create the route
              CreateRouteRequest createRouteReq = new CreateRouteRequest();
              createRouteReq.setDestinationCidrBlock(extVpnEndpoint.getVpc().getCidrBlock());
              createRouteReq.setInstanceId(vpnEndpoint.getInstance().getInstanceId());
              createRouteReq.setRouteTableId(routeTable.getRouteTableId());
              LOG.debug("About to create a route in " + vpnEndpoint.getVpc().getVpcId() + " to " + extVpnEndpoint.getVpc().getVpcId() + " in route table: " + routeTable.getRouteTableId());
              ec2Client.createRoute(createRouteReq);
              LOG.debug("Created route in " + vpnEndpoint.getVpc().getVpcId() + " to " + extVpnEndpoint.getVpc().getVpcId() + " in route table: " + routeTable.getRouteTableId());
            }
          }
        }
      }

    }

  }

  private void configureSecurityGroups(List<VPNEndpoint> vpnEndpoints) {
    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      String securityGroupName = vpc2vpcId;

      // Create Security Group
      CreateSecurityGroupRequest createSecGrpReq = new CreateSecurityGroupRequest();
      createSecGrpReq.setGroupName(securityGroupName);
      createSecGrpReq.setDescription("vpc2vpc Security Group");
      createSecGrpReq.setVpcId(vpnEndpoint.getVpc().getVpcId());
      LOG.debug("Creating security group in " + vpnEndpoint.getRegion().getRegionName());
      CreateSecurityGroupResult createSecGrpResult = ec2Client.createSecurityGroup(createSecGrpReq);
      String securityGroupId = createSecGrpResult.getGroupId();
      LOG.debug("Created security group " + securityGroupId + " in " + vpnEndpoint.getRegion().getRegionName());

      // Set the endpoint's security group
      vpnEndpoint.setSecurityGroupId(securityGroupId);

      // Get a list of external endpoint's EIPs
      List<String> ipRanges = new ArrayList();
      for (VPNEndpoint extVpnEndpoint : vpnEndpoints) {
        if (!vpnEndpoint.equals(extVpnEndpoint)) {
          ipRanges.add(extVpnEndpoint.getElasticIPAddress() + "/32");
        }
      }

      List<String> localIpRanges = new ArrayList();
      localIpRanges.add(vpnEndpoint.getVpc().getCidrBlock());

      // Create the IpPermissions
      List<IpPermission> ipPermissions = new ArrayList();

      IpPermission allTcpTraffic = new IpPermission();
      allTcpTraffic.setIpRanges(localIpRanges);
      allTcpTraffic.setIpProtocol("tcp");
      allTcpTraffic.setFromPort(1);
      allTcpTraffic.setToPort(65535);
      ipPermissions.add(allTcpTraffic);

      IpPermission allUdpTraffic = new IpPermission();
      allUdpTraffic.setIpRanges(localIpRanges);
      allUdpTraffic.setIpProtocol("udp");
      allUdpTraffic.setFromPort(1);
      allUdpTraffic.setToPort(65535);
      ipPermissions.add(allUdpTraffic);

      IpPermission allIcmpTraffic = new IpPermission();
      allIcmpTraffic.setIpRanges(localIpRanges);
      allIcmpTraffic.setIpProtocol("icmp");
      allIcmpTraffic.setFromPort(-1);
      allIcmpTraffic.setToPort(-1);
      ipPermissions.add(allIcmpTraffic);

      IpPermission ipPermUdp500 = new IpPermission();
      ipPermUdp500.setIpProtocol("udp");
      ipPermUdp500.setFromPort(500);
      ipPermUdp500.setToPort(500);
      ipPermUdp500.setIpRanges(ipRanges);
      ipPermissions.add(ipPermUdp500);

      IpPermission ipPermTcp500 = new IpPermission();
      ipPermTcp500.setIpProtocol("tcp");
      ipPermTcp500.setFromPort(500);
      ipPermTcp500.setToPort(500);
      ipPermTcp500.setIpRanges(ipRanges);
      ipPermissions.add(ipPermTcp500);

      IpPermission ipPermUdp4500 = new IpPermission();
      ipPermUdp4500.setIpProtocol("udp");
      ipPermUdp4500.setFromPort(4500);
      ipPermUdp4500.setToPort(4500);
      ipPermUdp4500.setIpRanges(ipRanges);
      ipPermissions.add(ipPermUdp4500);

      // Set permissions on security group
      AuthorizeSecurityGroupIngressRequest authSecGrpIngressReq = new AuthorizeSecurityGroupIngressRequest();
      authSecGrpIngressReq.setGroupId(securityGroupId);
      authSecGrpIngressReq.setIpPermissions(ipPermissions);
      LOG.debug("About to authorize SecurityGroup Ingress on : " + securityGroupId);

      // Apply security group rules.  Need to retry a few times since API takes a bit of time to realize the SG really does exist
      int retryCount = 0;
      boolean done = false;
      while (!done && retryCount < 3) {
        try {
          ec2Client.authorizeSecurityGroupIngress(authSecGrpIngressReq);
          done = true;
        } catch (Exception e) {
          try {
            Thread.sleep(5000);
          } catch (Exception ie) {
            // Eat it
          }
          if (retryCount > 3) {
            LOG.debug("Exceeded retries.  Throwing exception");
            throw new RuntimeException(e);
          } else {
            retryCount = retryCount + 1;
            LOG.debug("Caught exception.  Going to retry request.  Exception: " + e.getMessage());
          }
        }
      }
    }
  }

  private void associatePublicIP(List<VPNEndpoint> vpnEndpoints) throws Exception {
    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      Instance instance = vpnEndpoint.getInstance();

      // Associate Elastic IP (Public IP)
      AssociateAddressRequest assocAddrReq = new AssociateAddressRequest();
      assocAddrReq.setInstanceId(instance.getInstanceId());
      assocAddrReq.setAllocationId(vpnEndpoint.getElasticIPAllocationId());
      String associationId =
              ec2Client.associateAddress(assocAddrReq).getAssociationId();
      LOG.debug("Associated public IP " + vpnEndpoint.getElasticIPAddress() + " with instance " + instance);

    }

  }

  private void waitOnInstances(List<VPNEndpoint> vpnEndpoints) throws Exception {
    int timeoutInMinutes = 5; // TODO: Remove hardcoding and put in config
    boolean done = false;
    long startTime = System.currentTimeMillis();
    long endTime = startTime + (timeoutInMinutes * 60 * 1000);


    LOG.info(String.format("Waiting on EC2 VPN instances to launch..  This may take up to %d minutes", timeoutInMinutes));
    while (!done && (System.currentTimeMillis() < endTime)) {

      done = true;
      for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
        try {
          ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());
          String instanceId = vpnEndpoint.getInstance().getInstanceId();
          DescribeInstancesRequest descInstancesReq = new DescribeInstancesRequest().withInstanceIds(instanceId);
          List<Reservation> reservations = ec2Client.describeInstances(descInstancesReq).getReservations();
          Instance instance = reservations.get(0).getInstances().get(0);

          LOG.debug("Waiting on instances: " + instance.getInstanceId() + " state: " + instance.getState());
          if (!instance.getState().getName().equals("running")) {
            done = false;
          }
        } catch (Exception e) {
          LOG.debug("Ignoring exception: " + e.getMessage());
          e.printStackTrace();
        }
      }
      if (!done) {
        Thread.sleep(15 * 1000);
      }
    }

    LOG.debug("startTime: " + startTime);
    LOG.debug("endTime: " + endTime);
    LOG.debug("now: " + System.currentTimeMillis());

  }

  // Disable Src/Dest Check
  private void disableSrcDestCheck(List<VPNEndpoint> vpnEndpoints) {

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      Instance instance = vpnEndpoint.getInstance();

      List<NetworkInterface> networkInterfaces = ec2Client.describeNetworkInterfaces().getNetworkInterfaces();
      for (NetworkInterface nic : networkInterfaces) {
        if (nic.getAttachment().getInstanceId().equals(instance.getInstanceId())) {
          ModifyNetworkInterfaceAttributeRequest modifyNicAttribute = new ModifyNetworkInterfaceAttributeRequest();
          modifyNicAttribute.setNetworkInterfaceId(nic.getNetworkInterfaceId());
          modifyNicAttribute.setSourceDestCheck(false);
          ec2Client.modifyNetworkInterfaceAttribute(modifyNicAttribute);
          LOG.debug("Disabled Src/Dest check on " + instance.getInstanceId());
        }
      }
    }
  }

  private void createTags(List<VPNEndpoint> vpnEndpoints) {

    // Setup Tags
    List<Tag> commonTags = new ArrayList();
    commonTags.add(new Tag("Name", "vpc2vpc"));
    commonTags.add(new Tag("vpc2vpc:id", vpc2vpcId));
    commonTags.add(new Tag("vpc2vpc:created_on", Long.valueOf(System.currentTimeMillis()).toString()));

    List<String> vpcIdList = new ArrayList();
    List<String> subnetIdList = new ArrayList();
    List<String> publicIpList = new ArrayList();

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      Vpc vpc = vpnEndpoint.getVpc();
      Subnet subnet = vpnEndpoint.getSubnet();
      vpcIdList.add(vpc.getVpcId());
      subnetIdList.add(subnet.getSubnetId());
      publicIpList.add(vpnEndpoint.getElasticIPAddress());
    }

    String vpcIds = new String();
    for (String vpcId : vpcIdList) {
      if (vpcIds.length() > 0) {
        vpcIds = vpcIds + ",";
      }
      vpcIds = vpcIds + vpcId;
    }
    commonTags.add(new Tag("vpc2vpc:vpc_id_list", vpcIds));

    String subnetIds = new String();
    for (String subnetId : subnetIdList) {
      if (subnetIds.length() > 0) {
        subnetIds = subnetIds + ",";
      }
      subnetIds = subnetIds + subnetId;
    }
    commonTags.add(new Tag("vpc2vpc:subnet_id_list", subnetIds));

    String publicIps = new String();
    for (String publicIp : publicIpList) {
      if (publicIps.length() > 0) {
        publicIps = publicIps + ",";
      }
      publicIps = publicIps + publicIp;
    }
    commonTags.add(new Tag("vpc2vpc:public_ip_list", publicIps));

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());

      // Create a new list of tags including common tags and endpoint specific tags
      List<Tag> tags = new ArrayList();
      tags.addAll(commonTags);
      tags.add(new Tag("vpc2vpc:public_ip", vpnEndpoint.getElasticIPAddress()));

      // Create tags
      List<String> resourceIds = new ArrayList();
      resourceIds.add(vpnEndpoint.getInstance().getInstanceId());
      CreateTagsRequest createTagsRequest = new CreateTagsRequest(resourceIds, tags);
      LOG.debug("About to create tags: " + createTagsRequest);
      ec2Client.createTags(createTagsRequest);
      LOG.debug("Created tags: " + createTagsRequest);
    }
  }

  private void launchInstances(List<VPNEndpoint> vpnEndpoints) throws Exception {

    ApplicationConfig appConfig = ApplicationConfig.getInstance();

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      Region region = vpnEndpoint.getRegion();
      ec2Client.setEndpoint(region.getEndpoint());

      // Get the AMI for the region
      String amiKey = "ami." + region.getRegionName();
      String amiId = appConfig.get(amiKey);
      if (amiId == null) {
        String msg = "Unable to find AMI in " + region.getRegionName();
        LOG.error(msg);
        throw new RuntimeException(msg);
      }

      // Get the security group for the instance
      String securityGroupId = vpnEndpoint.getSecurityGroupId();
      List<String> securityGroupIds = new ArrayList();
      securityGroupIds.add(securityGroupId);

      // Setup the instance request object
      LOG.debug("Setting up RunInstancesRequest for instance in " + vpnEndpoint.getVpc().getCidrBlock() + " - " + region.getEndpoint());
      RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
      runInstancesRequest.setMinCount(1);
      runInstancesRequest.setMaxCount(1);
      runInstancesRequest.setImageId(amiId);
      runInstancesRequest.setSecurityGroupIds(securityGroupIds);
      runInstancesRequest.setInstanceType(InstanceType.T1Micro); // TODO: Make this configurable
      runInstancesRequest.setSubnetId(vpnEndpoint.getSubnet().getSubnetId());
      runInstancesRequest.setUserData(generateCloudInitScript(vpnEndpoint, vpnEndpoints));
      //runInstancesRequest.setKeyName("amazon"); // TODO: Remove this or make this configurable

      // Launch the instance
      LOG.debug("Issuing runInstances with: " + runInstancesRequest);
      RunInstancesResult result = ec2Client.runInstances(runInstancesRequest);
      Reservation reservation = result.getReservation();
      Instance instance = reservation.getInstances().get(0);  // Should be just one
      vpnEndpoint.setInstance(instance);
      LOG.debug("Launched instance: " + instance);
    }
  }

  private String generateCloudInitScript(VPNEndpoint originVpnEndpoint, List<VPNEndpoint> vpnEndpoints) throws Exception {

    InputStream cloudInitTmplInputStream = this.getClass().getClassLoader().getResourceAsStream(CLOUD_INIT_TEMPLATE);
    byte[] cloudInitTmplBytes = IOUtils.toByteArray(cloudInitTmplInputStream);
    String cloudInitTmplStr = new String(cloudInitTmplBytes);

    InputStream cloudInitIPSecTmplInputStream = this.getClass().getClassLoader().getResourceAsStream(CLOUD_INIT_IPSEC_CONF_TEMPLATE);
    byte[] cloudInitIPSecTmplBytes = IOUtils.toByteArray(cloudInitIPSecTmplInputStream);
    String cloudInitIPSecTmplStr = new String(cloudInitIPSecTmplBytes);

    String ipsecConfigs = new String();

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      if (!originVpnEndpoint.getVpc().equals(vpnEndpoint.getVpc())) {
        String ipsecConfig = cloudInitIPSecTmplStr;
        ipsecConfig = ipsecConfig.replaceAll("_SRC_VPC_ID_", originVpnEndpoint.getVpc().getVpcId());
        ipsecConfig = ipsecConfig.replaceAll("_DEST_VPC_ID_", vpnEndpoint.getVpc().getVpcId());
        ipsecConfig = ipsecConfig.replaceAll("_SRC_VPC_EIP_", originVpnEndpoint.getElasticIPAddress());
        ipsecConfig = ipsecConfig.replaceAll("_DEST_VPC_EIP_", vpnEndpoint.getElasticIPAddress());

        ipsecConfig = ipsecConfig.replaceAll("_SRC_VPC_CIDR_", originVpnEndpoint.getVpc().getCidrBlock());
        ipsecConfig = ipsecConfig.replaceAll("_DEST_VPC_CIDR_", vpnEndpoint.getVpc().getCidrBlock());
        ipsecConfig = ipsecConfig.replaceAll("_VPC2VPC_ID_", vpc2vpcId);

        ipsecConfigs = ipsecConfigs.concat(ipsecConfig);
      }
    }

    String cloudInitScript = cloudInitTmplStr.replaceAll("_VPC_CONFIG_", ipsecConfigs);
    LOG.debug("cloudInitScript=" + cloudInitScript);

    return new String(Base64.encodeBase64(cloudInitScript.getBytes()));
  }

  /**
   * Allocate Elastic IPs as needed and assign it to the endpoints. If there are
   * any errors, roll back by releasing all allocated IPs if possible
   *
   * @param vpnEndpoints
   */
  private void allocateElasticIPs(List<VPNEndpoint> vpnEndpoints) {

    for (VPNEndpoint vpnEndpoint : vpnEndpoints) {
      ec2Client.setEndpoint(vpnEndpoint.getRegion().getEndpoint());
      AllocateAddressResult allocAddrResult = ec2Client.allocateAddress(new AllocateAddressRequest().withDomain(DomainType.Vpc));
      String publicIp = allocAddrResult.getPublicIp();
      vpnEndpoint.setElasticIPAddress(publicIp);
      vpnEndpoint.setElasticIPAllocationId(allocAddrResult.getAllocationId());
      LOG.debug("Allocated elastic IP " + publicIp + " in " + vpnEndpoint.getRegion().getEndpoint());
    }
  }

  /**
   * Checks to see if the endpoint has a subnet set and if so makes sure the
   * subnet is a 'public' subnet. If it doesn't then unset the subnet. If no
   * subnet is set, then find a 'public' subnet and set it.
   *
   * @param endpoint
   */
  private void updateOrConfirmPublicSubnet(VPNEndpoint endpoint) {

    Subnet selectedSubnet = null;
    Vpc vpc = endpoint.getVpc();

    if (endpoint.getSubnet() != null) {
      selectedSubnet = endpoint.getSubnet();
    }

    List<RouteTable> publicRouteTables = new ArrayList();

    // Get the route tables that have a default route to an IGW
    List<RouteTable> vpcRouteTables = vpcIdRouteTableMap.get(vpc.getVpcId());
    for (RouteTable routeTable : vpcRouteTables) {
      LOG.debug(routeTable);
      for (Route route : routeTable.getRoutes()) {
        if (route.getDestinationCidrBlock().equals("0.0.0.0/0")
                && route.getGatewayId() != null
                && route.getState().equals("active")) {
          LOG.debug("Public Route: " + route);
          if (!publicRouteTables.contains(routeTable)) {
            publicRouteTables.add(routeTable);
          }
        }
      }
    }

    List<Subnet> publicSubnets = new ArrayList();

    // Get a list of public subnets
    for (RouteTable routeTable : publicRouteTables) {
      for (RouteTableAssociation assoc : routeTable.getAssociations()) {
        Subnet subnet = subnetIdMap.get(assoc.getSubnetId());
        if (!publicSubnets.contains(subnet)) {
          publicSubnets.add(subnet);
          LOG.debug("Public Subnet: " + subnet);
        }
      }
    }

    if (selectedSubnet != null) {
      if (publicSubnets.contains(selectedSubnet)) {
        LOG.debug("The user selected subnet is public : " + selectedSubnet);
      } else {
        LOG.error("Subnet " + selectedSubnet.getCidrBlock() + " (" + selectedSubnet.getSubnetId() + ") is not a public subnet");
        endpoint.setSubnet(null);
      }
    } else {
      // Selected a public subnet for the endpoint
      if (publicSubnets.isEmpty()) {
        LOG.error("No public subnets available for VPC " + vpc.getCidrBlock() + " (" + vpc.getVpcId() + ")");
      } else {
        // Get the first public subnet found
        for (Subnet subnet : publicSubnets) {
          if (subnet.getAvailableIpAddressCount() > 0) {
            selectedSubnet = subnet;
            break;
          }
        }
        if (selectedSubnet == null) {
          LOG.error("No public subnets available with enough available IPs for VPC " + vpc.getCidrBlock() + " (" + vpc.getVpcId() + ")");
        } else {
          selectedSubnet = publicSubnets.get(0);
          endpoint.setSubnet(selectedSubnet);
          LOG.debug("Selected public subnet " + selectedSubnet.getCidrBlock() + " (" + selectedSubnet.getSubnetId() + ") for VPC " + vpc.getCidrBlock() + " (" + vpc.getVpcId() + ")");
        }
      }
    }

  }

  /**
   * Sets up some lists and maps that are used through out this class to lookup
   * VPCs
   */
  private void populateLookupData() {

    HashMap<Region, List> regionVpcMap = VPCHelper.getInstance().listRegionVpcs(awsCreds);
    HashMap<Region, List> regionSubnetMap = VPCHelper.getInstance().listRegionSubnets(awsCreds);
    HashMap<Region, List> regionRouteTableMap = VPCHelper.getInstance().listRegionRouteTables(awsCreds);

    vpcs = new ArrayList();
    for (List<Vpc> regionVpcs : regionVpcMap.values()) {
      vpcs.addAll(regionVpcs);
    }

    subnets = new ArrayList();
    for (List<Subnet> regionSubnets : regionSubnetMap.values()) {
      subnets.addAll(regionSubnets);
    }

    vpcIdRegionMap = new HashMap();
    for (Region region : regionVpcMap.keySet()) {
      List<Vpc> regionVpcs = regionVpcMap.get(region);
      for (Vpc vpc : regionVpcs) {
        vpcIdRegionMap.put(vpc.getVpcId(), region);
      }
    }

    subnetIdRegionMap = new HashMap();
    for (Region region : regionSubnetMap.keySet()) {
      List<Subnet> regionSubnets = regionSubnetMap.get(region);
      for (Subnet subnet : regionSubnets) {
        subnetIdRegionMap.put(subnet.getSubnetId(), region);
      }
    }

    vpcIdMap = new HashMap();
    for (Vpc vpc : vpcs) {
      vpcIdMap.put(vpc.getVpcId(), vpc);
    }

    vpcCidrMap = new HashMap();
    for (Vpc vpc : vpcs) {
      String cidrBlock = vpc.getCidrBlock();
      List<Vpc> cidrVpcs = vpcCidrMap.get(cidrBlock);
      if (cidrVpcs == null) {
        cidrVpcs = new ArrayList();
        vpcCidrMap.put(cidrBlock, cidrVpcs);
      }
      cidrVpcs.add(vpc);
    }

    subnetIdMap = new HashMap();
    for (Subnet subnet : subnets) {
      subnetIdMap.put(subnet.getSubnetId(), subnet);
    }

    subnetCidrMap = new HashMap();
    for (Subnet subnet : subnets) {
      String cidrBlock = subnet.getCidrBlock();
      List<Subnet> cidrSubnets = subnetCidrMap.get(cidrBlock);
      if (cidrSubnets == null) {
        cidrSubnets = new ArrayList();
        subnetCidrMap.put(cidrBlock, cidrSubnets);
      }
      cidrSubnets.add(subnet);
    }

    vpcIdRouteTableMap = new HashMap();
    for (Region region : regionRouteTableMap.keySet()) {
      List<RouteTable> routeTables = regionRouteTableMap.get(region);
      for (RouteTable routeTable : routeTables) {
        String vpcId = routeTable.getVpcId();
        List<RouteTable> vpcRouteTables = vpcIdRouteTableMap.get(vpcId);
        if (vpcRouteTables == null) {
          vpcRouteTables = new ArrayList();
          vpcIdRouteTableMap.put(routeTable.getVpcId(), vpcRouteTables);
        }
        vpcRouteTables.add(routeTable);
      }
    }
  }

  /**
   * Checks to see if there are duplicate VPN Endpoints. Also checks to see if
   * there are duplicate CIDR blocks.
   *
   * @param vpnEndpoints
   * @return
   */
  private boolean areEndpointsDuplicate(List<VPNEndpoint> vpnEndpoints) {

    HashMap<String, VPNEndpoint> endpointVpcIdMap = new HashMap();
    for (VPNEndpoint endpoint : vpnEndpoints) {
      if (endpointVpcIdMap.containsKey(endpoint.getVpc().getVpcId())) {
        return true;
      } else {
        endpointVpcIdMap.put(endpoint.getVpc().getVpcId(), endpoint);
      }
    }

    HashMap<String, VPNEndpoint> endpointCidrMap = new HashMap();
    for (VPNEndpoint endpoint : vpnEndpoints) {
      if (endpointCidrMap.containsKey(endpoint.getVpc().getCidrBlock())) {
        return true;
      } else {
        endpointCidrMap.put(endpoint.getVpc().getCidrBlock(), endpoint);
      }
    }

    return false;
  }

  /**
   * Creates a VPNEndpoint object from the user provided input
   *
   * @param endpointArg
   * @return
   */
  private VPNEndpoint getVpnEndpoint(String endpointArg) {
    VPNEndpoint endpoint = new VPNEndpoint();

    if (isVpcId(endpointArg)) {
      Vpc vpc = vpcIdMap.get(endpointArg);
      if (vpc == null) {
        LOG.error("Unable to find a VPC for ID " + endpointArg);
        return null;
      }
      endpoint.setVpc(vpc);

      LOG.debug(vpc + " matched VPC id " + endpointArg);

    } else if (isSubnetId(endpointArg)) {
      Subnet subnet = subnetIdMap.get(endpointArg);
      if (subnet == null) {
        LOG.error("Unable to find Subnet for ID " + endpointArg);
        return null;
      }
      endpoint.setSubnet(subnet);
      endpoint.setVpc(vpcIdMap.get(subnet.getVpcId()));

      LOG.debug(subnet + " matched Subnet id " + endpointArg);

    } else if (isCidr(endpointArg)) {

      List<Subnet> subnetsMatchingCidr = subnetCidrMap.get(endpointArg);
      if (subnetsMatchingCidr != null) {
        for (Subnet subnet : subnetsMatchingCidr) {
          LOG.debug(subnet + " matches CIDR " + endpointArg);
        }
      }

      if (subnetsMatchingCidr != null && subnetsMatchingCidr.size() > 1) {
        LOG.error("More than one subnet matches the CIDR " + endpointArg + ".  Please specify the Subnet/VPC by ID");
        return null;
      } else if (subnetsMatchingCidr != null) {
        endpoint.setSubnet(subnetsMatchingCidr.get(0));
        endpoint.setVpc(vpcIdMap.get(endpoint.getSubnet().getVpcId()));
      }

      List<Vpc> vpcsMatchingCidr = vpcCidrMap.get(endpointArg);
      if (vpcsMatchingCidr != null) {
        for (Vpc vpc : vpcsMatchingCidr) {
          LOG.debug(vpc + " matches CIDR " + endpointArg);
        }
      }

      if (vpcsMatchingCidr != null && vpcsMatchingCidr.size() > 1) {
        LOG.error("More than one VPC matches the CIDR " + endpointArg + ".  Please specify the Subnet/VPC by ID");

        return null;
      } else if (vpcsMatchingCidr != null) {
        endpoint.setVpc(vpcsMatchingCidr.get(0));
      }

      if (endpoint.getVpc() == null) {
        LOG.error("Unable to find a matching Subnet/VPC for CIDR " + endpointArg);
        return null;
      }

    } else {
      return null;
    }

    endpoint.setRegion(vpcIdRegionMap.get(endpoint.getVpc().getVpcId()));

    return endpoint;
  }

  /**
   * Checks if string is a CIDR
   *
   * @param cidrNotation
   * @return
   */
  private boolean isCidr(String cidrNotation) {
    String ipv4Cidr = "(((2(5[0-5]|[0-4][0-9])|[01]?[0-9][0-9]?)\\.){3}(2(5[0-5]|[0-4][0-9])|[01]?[0-9][0-9]?)(/(3[012]|[12]?[0-9])))";
    return cidrNotation.matches(ipv4Cidr);
  }

  /**
   * Checks if string is a subnet ID
   *
   * @param subnetId
   * @return
   */
  private boolean isSubnetId(String subnetId) {
    String subnetRegex = "^subnet-[a-zA-Z0-9]*";
    return subnetId.matches(subnetRegex);
  }

  /**
   * Checks if string is a VPC ID
   *
   * @param vpcId
   * @return
   */
  public boolean isVpcId(String vpcId) {
    String vpcRegex = "^vpc-[a-zA-Z0-9]*";
    return vpcId.matches(vpcRegex);
  }
}

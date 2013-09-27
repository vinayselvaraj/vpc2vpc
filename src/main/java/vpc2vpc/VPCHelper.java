package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.RouteTable;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Vpc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;

/**
 * VPCHelper
 *
 * @author Vinay Selvaraj
 */
public class VPCHelper {

  private Logger LOG = Logger.getLogger(VPCHelper.class);
  private static VPCHelper instance;

  private VPCHelper() {
  }

  public static VPCHelper getInstance() {
    if (instance == null) {
      instance = new VPCHelper();
    }
    return instance;
  }

  public List<Vpc> listVpcs(AWSCredentials awsCreds) {
    List<Vpc> vpcs = new ArrayList();

    HashMap<Region, List> regionVpcMap = listRegionVpcs(awsCreds);
    for (List<Vpc> regionVpcs : regionVpcMap.values()) {
      vpcs.addAll(regionVpcs);
    }

    return vpcs;
  }

  public HashMap<Region, List> listRegionVpcs(AWSCredentials awsCreds) {

    AmazonEC2Client ec2Client = new AmazonEC2Client(awsCreds);
    List<Region> regions = new ArrayList();

    DescribeRegionsResult descRegionsResult = ec2Client.describeRegions();
    if (descRegionsResult != null) {
      regions = descRegionsResult.getRegions();
    }

    HashMap<Region, List> regionVpcs = new HashMap();

    ExecutorService listVPCExecutor = Executors.newFixedThreadPool(8);
    for (Region region : regions) {
      List<Vpc> vpcs = new ArrayList();
      regionVpcs.put(region, vpcs);

      Runnable worker = new ListVPCRunnable(awsCreds, region, vpcs);
      listVPCExecutor.execute(worker);
    }

    listVPCExecutor.shutdown();
    try {
      listVPCExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      LOG.error("Caught InterruptedException: " + e.getMessage());
    }

    return regionVpcs;
  }

  public List<Subnet> listSubnets(AWSCredentials awsCreds) {
    List<Subnet> subnets = new ArrayList();

    HashMap<Region, List> regionSubnetMap = listRegionSubnets(awsCreds);
    for (List<Subnet> regionSubnets : regionSubnetMap.values()) {
      subnets.addAll(regionSubnets);
    }

    return subnets;
  }

  public HashMap<Region, List> listRegionSubnets(AWSCredentials awsCreds) {
    AmazonEC2Client ec2Client = new AmazonEC2Client(awsCreds);
    List<Region> regions = new ArrayList();

    DescribeRegionsResult descRegionsResult = ec2Client.describeRegions();
    if (descRegionsResult != null) {
      regions = descRegionsResult.getRegions();
    }

    HashMap<Region, List> regionSubnetsMap = new HashMap();

    ExecutorService listSubnetExecutor = Executors.newFixedThreadPool(8);
    for (Region region : regions) {
      List<Subnet> subnets = new ArrayList();
      regionSubnetsMap.put(region, subnets);

      Runnable worker = new ListSubnetRunnable(awsCreds, region, subnets);
      listSubnetExecutor.execute(worker);
    }

    listSubnetExecutor.shutdown();
    try {
      listSubnetExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      LOG.error("Caught InterruptedException: " + e.getMessage());
    }

    return regionSubnetsMap;
  }

  public HashMap<Region, List> listRegionRouteTables(AWSCredentials awsCreds) {
    AmazonEC2Client ec2Client = new AmazonEC2Client(awsCreds);
    List<Region> regions = new ArrayList();

    DescribeRegionsResult descRegionsResult = ec2Client.describeRegions();
    if (descRegionsResult != null) {
      regions = descRegionsResult.getRegions();
    }

    HashMap<Region, List> regionRouteTablesMap = new HashMap();

    ExecutorService listRouteTablesExecutor = Executors.newFixedThreadPool(8);
    for (Region region : regions) {
      List<RouteTable> routeTables = new ArrayList();
      regionRouteTablesMap.put(region, routeTables);

      Runnable worker = new ListRouteTableRunnable(awsCreds, region, routeTables);
      listRouteTablesExecutor.execute(worker);
    }

    listRouteTablesExecutor.shutdown();
    try {
      listRouteTablesExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      LOG.error("Caught InterruptedException: " + e.getMessage());
    }

    return regionRouteTablesMap;
  }
}

class ListVPCRunnable implements Runnable {

  private Logger LOG = Logger.getLogger(ListVPCRunnable.class);
  private AmazonEC2Client ec2Client;
  private Region region;
  private List<Vpc> vpcs;

  public ListVPCRunnable(AWSCredentials awsCreds, Region region, List<Vpc> vpcs) {
    this.region = region;
    this.vpcs = vpcs;
    ec2Client = new AmazonEC2Client(awsCreds);
    ec2Client.setEndpoint(region.getEndpoint());
    LOG.debug("Set endpoint to " + region.getEndpoint());
  }

  public void run() {
    LOG.debug("Running describeVpcs() in " + region.getRegionName());
    vpcs.addAll(ec2Client.describeVpcs().getVpcs());
    LOG.debug("Completed describeVpcs() in " + region.getRegionName());
  }
}

class ListSubnetRunnable implements Runnable {

  private Logger LOG = Logger.getLogger(ListSubnetRunnable.class);
  private AmazonEC2Client ec2Client;
  private Region region;
  private List<Subnet> subnets;

  public ListSubnetRunnable(AWSCredentials awsCreds, Region region, List<Subnet> subnets) {
    this.region = region;
    this.subnets = subnets;
    ec2Client = new AmazonEC2Client(awsCreds);
    ec2Client.setEndpoint(region.getEndpoint());
    LOG.debug("Set endpoint to " + region.getEndpoint());
  }

  public void run() {
    LOG.debug("Running describeSubnets() in " + region.getRegionName());
    subnets.addAll(ec2Client.describeSubnets().getSubnets());
    LOG.debug("Completed describeSubnets() in " + region.getRegionName());
  }
}

class ListRouteTableRunnable implements Runnable {

  private Logger LOG = Logger.getLogger(ListRouteTableRunnable.class);
  private AmazonEC2Client ec2Client;
  private Region region;
  private List<RouteTable> routeTables;

  public ListRouteTableRunnable(AWSCredentials awsCreds, Region region, List<RouteTable> routeTables) {
    this.region = region;
    this.routeTables = routeTables;
    ec2Client = new AmazonEC2Client(awsCreds);
    ec2Client.setEndpoint(region.getEndpoint());
    LOG.debug("Set endpoint to " + region.getEndpoint());
  }

  public void run() {
    LOG.debug("Running describeRouteTables() in " + region.getRegionName());
    routeTables.addAll(ec2Client.describeRouteTables().getRouteTables());
    LOG.debug("Completed describeRouteTables() in " + region.getRegionName());
  }
}
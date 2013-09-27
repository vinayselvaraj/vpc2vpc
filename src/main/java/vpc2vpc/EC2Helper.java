package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.Reservation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;


/**
 * InstanceHelper
 *
 * @author Vinay Selvaraj
 */
public class EC2Helper {

  private Logger LOG = Logger.getLogger(EC2Helper.class);
  private static EC2Helper instance;

  private EC2Helper() {
  }

  public static EC2Helper getInstance() {
    if (instance == null) {
      instance = new EC2Helper();
    }
    return instance;
  }
  
  public HashMap<Region, List> listRegionInstances(AWSCredentials awsCreds) {

    AmazonEC2Client ec2Client = new AmazonEC2Client(awsCreds);
    List<Region> regions = new ArrayList();

    DescribeRegionsResult descRegionsResult = ec2Client.describeRegions();
    if (descRegionsResult != null) {
      regions = descRegionsResult.getRegions();
    }

    HashMap<Region, List> regionInstances = new HashMap();

    ExecutorService listInstanceExecutor = Executors.newFixedThreadPool(8);
    for (Region region : regions) {
      List<Instance> instances = new ArrayList();
      regionInstances.put(region, instances);

      Runnable worker = new ListInstanceRunnable(awsCreds, region, instances);
      listInstanceExecutor.execute(worker);
    }

    listInstanceExecutor.shutdown();
    try {
      listInstanceExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      LOG.error("Caught InterruptedException: " + e.getMessage());
    }

    return regionInstances;
  }
}
class ListInstanceRunnable implements Runnable {

  private Logger LOG = Logger.getLogger(ListInstanceRunnable.class);
  private AmazonEC2Client ec2Client;
  private Region region;
  private List<Instance> instances;

  public ListInstanceRunnable(AWSCredentials awsCreds, Region region, List<Instance> instances) {
    this.region = region;
    this.instances = instances;
    ec2Client = new AmazonEC2Client(awsCreds);
    ec2Client.setEndpoint(region.getEndpoint());
    LOG.debug("Set endpoint to " + region.getEndpoint());
  }

  public void run() {
    LOG.debug("Running describeInstances() in " + region.getRegionName());
    List<Reservation> reservations = ec2Client.describeInstances().getReservations();
    for(Reservation reservation : reservations) {
      instances.addAll(reservation.getInstances());
    }
    LOG.debug("Completed describeInstances() in " + region.getRegionName());
  }
}

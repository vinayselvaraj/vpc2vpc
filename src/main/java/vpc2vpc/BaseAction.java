package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.Region;
import java.util.List;

/**
 * Action
 *
 * @author Vinay Selvaraj
 */
public abstract class BaseAction {

  protected String[] args;
  protected AWSCredentials awsCreds;
  protected List<Region> regions;
  protected AmazonEC2Client ec2Client;

  public BaseAction(String[] args, AWSCredentials awsCreds) {
    this.args = args;
    this.awsCreds = awsCreds;

    ec2Client = new AmazonEC2Client(awsCreds);
    DescribeRegionsResult descRegionsResult = ec2Client.describeRegions();
    if (descRegionsResult != null) {
      regions = descRegionsResult.getRegions();
    }

  }

  public abstract void run();
}

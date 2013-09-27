package vpc2vpc;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Vpc;

/**
 * VPNEndpoint
 *
 * @author Vinay Selvaraj
 */
public class VPNEndpoint {

  private Region region;
  private Vpc vpc;
  private Subnet subnet;
  private String securityGroupId;
  private String elasticIPAddress;
  private String elasticIPAllocationId;
  private Instance instance;

  public Vpc getVpc() {
    return vpc;
  }

  public void setVpc(Vpc vpc) {
    this.vpc = vpc;
  }

  public Subnet getSubnet() {
    return subnet;
  }

  public void setSubnet(Subnet subnet) {
    this.subnet = subnet;
  }

  public Region getRegion() {
    return region;
  }

  public void setRegion(Region region) {
    this.region = region;
  }

  public String getElasticIPAddress() {
    return elasticIPAddress;
  }

  public void setElasticIPAddress(String elasticIPAddress) {
    this.elasticIPAddress = elasticIPAddress;
  }

  public Instance getInstance() {
    return instance;
  }

  public void setInstance(Instance instance) {
    this.instance = instance;
  }

  public String getElasticIPAllocationId() {
    return elasticIPAllocationId;
  }

  public void setElasticIPAllocationId(String elasticIPAllocationId) {
    this.elasticIPAllocationId = elasticIPAllocationId;
  }

  public String getSecurityGroupId() {
    return securityGroupId;
  }

  public void setSecurityGroupId(String securityGroupId) {
    this.securityGroupId = securityGroupId;
  }

  @Override
  public String toString() {
    return String.format("region:%s, vpc:%s, subnet:%s, instance:%s", region, vpc, subnet, instance);
  }
}

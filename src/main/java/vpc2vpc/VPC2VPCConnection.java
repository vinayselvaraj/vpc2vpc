package vpc2vpc;

import java.util.Date;
import java.util.HashMap;

/**
 * VPC2VPCConnection Bean
 *
 * @author Vinay Selvaraj
 */
public class VPC2VPCConnection {

  private String id;
  private Date createdOn;
  private HashMap<String, VPNEndpoint> vpcIdVpnEndpoint;

  public VPC2VPCConnection(String id, Date createdOn) {
    this.id = id;
    this.createdOn = createdOn;
    vpcIdVpnEndpoint = new HashMap();
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Date getCreatedOn() {
    return createdOn;
  }

  public void setCreatedOn(Date createdOn) {
    this.createdOn = createdOn;
  }

  public HashMap<String, VPNEndpoint> getVpcIdVpnEndpoint() {
    return vpcIdVpnEndpoint;
  }

  public void setVpcIdVpnEndpoint(HashMap<String, VPNEndpoint> vpcIdVpnEndpoint) {
    this.vpcIdVpnEndpoint = vpcIdVpnEndpoint;
  }
}

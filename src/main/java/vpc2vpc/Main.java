package vpc2vpc;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import java.util.ArrayList;

/**
 * vpc2vpc Main Class
 *
 * @author Vinay Selvaraj
 */
public class Main {

  private static void showHelp() {
    System.out.println("SYNTAX: vpc2vpc <list|create|delete> [options]");
  }

  public static void main(String[] args) {

    ArrayList<String> validOptions = new ArrayList();
    validOptions.add("create");
    validOptions.add("list");
    validOptions.add("delete");

    if (args.length == 0 || validOptions.contains(args[0]) == false) {
      showHelp();
      System.exit(1);
    }

    // Get credentials
    String accessKey = System.getenv("AWS_ACCESS_KEY_ID").trim();
    String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY").trim();

    if (accessKey == null || secretKey == null) {
      System.err.println("Please set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables");
      System.exit(1);
    }
    
    AWSCredentials awsCreds = new BasicAWSCredentials(accessKey, secretKey);

    if (args[0].equals("list")) {
      new ListConnections(args, awsCreds).run();
    }

    if (args[0].equals("create")) {
      new CreateConnection(args, awsCreds).run();
    }

    if (args[0].equals("delete")) {
      new DeleteConnection(args, awsCreds).run();
    }

  }
}

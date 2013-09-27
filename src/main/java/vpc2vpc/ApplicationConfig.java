package vpc2vpc;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.log4j.Logger;

/**
 * Singleton class which is used to load the application configuration
 *
 * @author Vinay Selvaraj
 */
public class ApplicationConfig {
  
  private static ApplicationConfig instance;
  private Properties props;
  private final String APP_CONFIG_FILENAME = "application.properties";
  private Logger LOG = Logger.getLogger(ApplicationConfig.class);
  
  private ApplicationConfig() throws IOException {
    props = new Properties();
    InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(APP_CONFIG_FILENAME);
    if(inputStream == null) {
      LOG.error("Unable to load application.properties");
    } else {
      props.load(inputStream);
    }
  }
  
  public static ApplicationConfig getInstance() throws IOException  {
    if(instance == null) {
      instance = new ApplicationConfig();
    }
    return instance;
  }
  
  public String get(String key) {
    return props.getProperty(key);
  }
}

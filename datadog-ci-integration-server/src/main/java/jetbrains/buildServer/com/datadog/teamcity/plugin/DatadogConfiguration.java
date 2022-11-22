package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DatadogConfiguration {

    @Bean
    public DatadogClient datadogClient() {
        return new DatadogClient(new RestTemplate(), new ObjectMapper());
    }
}

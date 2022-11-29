package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.RetryInformation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DatadogConfiguration {

    private static final int MAX_RETRIES = 3;
    private static final int BACKOFF_SECONDS = 10;

    @Bean
    public DatadogClient datadogClient(ObjectMapper objectMapper) {
        return new DatadogClient(new RestTemplate(), objectMapper, new RetryInformation(MAX_RETRIES, BACKOFF_SECONDS));
    }

    @Bean
    public ObjectMapper objectMapper(){
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(Include.NON_NULL);
        return mapper;
    }
}

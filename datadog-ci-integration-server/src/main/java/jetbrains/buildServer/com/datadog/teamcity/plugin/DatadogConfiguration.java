package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DatadogConfiguration {

    @Bean
    public DatadogClient datadogClient(ObjectMapper objectMapper) {
        return new DatadogClient(new RestTemplate(), objectMapper);
    }

    @Bean
    public ObjectMapper objectMapper(){
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(Include.NON_NULL);
        return mapper;
    }
}

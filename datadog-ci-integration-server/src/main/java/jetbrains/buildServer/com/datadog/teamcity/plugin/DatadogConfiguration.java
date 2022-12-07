package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.com.datadog.teamcity.plugin.DatadogClient.RetryInformation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DatadogConfiguration {

    private static final int MAX_RETRIES = 3;
    private static final int BACKOFF_SECONDS = 10;
    private static final int CONNECTION_TIMEOUT_MS = 10000; // 10 seconds

    @Bean
    public DatadogClient datadogClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        return new DatadogClient(restTemplate, objectMapper, new RetryInformation(MAX_RETRIES, BACKOFF_SECONDS));
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        requestFactory.setReadTimeout(CONNECTION_TIMEOUT_MS);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(requestFactory);
        return restTemplate;
    }

    @Bean
    public ObjectMapper objectMapper(){
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(Include.NON_NULL);
        return mapper;
    }
}

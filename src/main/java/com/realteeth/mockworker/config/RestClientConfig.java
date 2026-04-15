package com.realteeth.mockworker.config;

import com.realteeth.mockworker.client.MockWorkerProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Dedicated client for the Mock Worker. Short, bounded timeouts: we never want a
     * slow worker to pin an HTTP thread — better to time out and let the scheduler retry.
     */
    @Bean
    public RestClient mockWorkerRestClient(MockWorkerProperties props) {
        Duration connect = props.connectTimeout() != null ? props.connectTimeout() : Duration.ofSeconds(2);
        Duration read = props.readTimeout() != null ? props.readTimeout() : Duration.ofSeconds(5);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connect.toMillis());
        factory.setReadTimeout((int) read.toMillis());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }
}

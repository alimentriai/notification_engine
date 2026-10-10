package com.a1.notify.config;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** AWS clients are created only when used. Credentials come from the default provider chain (IAM role in prod). */
@Configuration
public class AwsConfig {

    private <B extends AwsClientBuilder<B, ?>> B configure(B builder, NotifyProperties props) {
        builder.region(Region.of(props.getRegion()));
        if (props.getAwsEndpoint() != null && !props.getAwsEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.getAwsEndpoint().trim()));
        }
        return builder;
    }

    @Bean
    @ConditionalOnProperty(name = "notify.providers.mode", havingValue = "aws")
    public SesV2Client sesClient(NotifyProperties props) {
        return configure(SesV2Client.builder(), props).build();
    }

    @Bean
    @ConditionalOnProperty(name = "notify.providers.mode", havingValue = "aws")
    public SnsClient snsClient(NotifyProperties props) {
        return configure(SnsClient.builder(), props).build();
    }

    @Bean
    @ConditionalOnProperty(name = "notify.queue.enabled", havingValue = "true")
    public SqsClient sqsClient(NotifyProperties props) {
        return configure(SqsClient.builder(), props).build();
    }
}

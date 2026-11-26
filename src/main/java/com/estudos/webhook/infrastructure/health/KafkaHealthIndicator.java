package com.estudos.webhook.infrastructure.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Saúde do Kafka em {@code /actuator/health}: o broker é dependência crítica da recepção (sem ele
 * os webhooks recebem 503). Consulta o cluster com timeout curto, para o health não travar.
 */
@Component("kafka")
public class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final AdminClient adminClient;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        super("Kafka health check failed");
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        DescribeClusterResult cluster = adminClient.describeCluster(
                new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()));
        String clusterId = cluster.clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        int brokers = cluster.nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
        builder.up().withDetail("clusterId", clusterId).withDetail("brokers", brokers);
    }

    @Override
    public void destroy() {
        adminClient.close(Duration.ofSeconds(1));
    }
}

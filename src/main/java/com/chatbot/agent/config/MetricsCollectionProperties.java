package com.chatbot.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for pulling metrics out of Prometheus and AppDynamics into the local metric store.
 *
 * <p>Both collectors are disabled by default: Prometheus needs a running server, AppDynamics needs
 * a Controller and credentials. The app behaves exactly as before until you switch one on.
 */
@Configuration
@ConfigurationProperties(prefix = "metrics-collection")
@Data
public class MetricsCollectionProperties {

    private boolean enabled = false;

    /** How often to poll. Should be >= the finest source resolution (Prometheus step, AppD 1 min). */
    private long intervalSeconds = 60;

    /**
     * How far back each poll reaches. Deliberately larger than intervalSeconds so consecutive
     * windows overlap - a slow or restarted collector then cannot leave a hole. Duplicates are
     * absorbed by the unique key on metric_sample.
     */
    private long lookbackSeconds = 300;

    /** Delete samples older than this. 0 disables retention (table grows without bound). */
    private long retentionDays = 90;

    private Prometheus prometheus = new Prometheus();
    private AppDynamics appdynamics = new AppDynamics();

    @Data
    public static class Prometheus {
        private boolean enabled = false;

        /** Base URL of the Prometheus server, NOT this app's /actuator/prometheus. */
        private String url = "http://localhost:9090";

        /** Resolution of stored samples. Must be >= the Prometheus scrape_interval. */
        private String step = "60s";

        private int timeoutSeconds = 30;

        /**
         * PromQL queries to record. Each result series becomes rows in metric_sample, named by
         * the map key. Raw counters are recorded too so you can recompute rates over any window
         * later - a rate() baked at collection time cannot be undone.
         */
        private List<Query> queries = List.of();

        @Data
        public static class Query {
            /** Stored as metric_name. Keep stable - renaming splits your training series. */
            private String name;
            private String promql;
        }
    }

    @Data
    public static class AppDynamics {
        private boolean enabled = false;

        /** Controller base URL, e.g. https://<account>.saas.appdynamics.com */
        private String controllerUrl;

        /** Application name as registered by the agent (appdynamics.agent.applicationName). */
        private String applicationName = "agent-platform";

        /** Basic auth principal - AppD requires the user@account form. */
        private String username;

        /** Read from an env var. Never commit this. */
        private String password;

        private int timeoutSeconds = 30;

        /**
         * AppD metric paths, pipe-delimited exactly as shown in the Controller's Metric Browser.
         * Right-click any metric there -> "Copy Full Path".
         */
        private List<String> metricPaths = List.of(
                "Overall Application Performance|Average Response Time (ms)",
                "Overall Application Performance|Calls per Minute",
                "Overall Application Performance|Errors per Minute"
        );
    }
}

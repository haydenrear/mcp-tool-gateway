package com.hayden.mcptoolgateway.kubernetes;

import org.springframework.stereotype.Component;

@Component
public class K3sService {

    public record K3sDeployResult (String err, boolean success, String host) {
        public K3sDeployResult(boolean success, String err) {
            this(err, success, "");
        }

        public K3sDeployResult(String err) {
            this(err, false, null);
        }
    }

    public K3sDeployResult doDeployGetValidDeployment() {
        return new K3sDeployResult("Not implemented");
    }

}

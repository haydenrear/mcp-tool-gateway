package com.hayden.mcptoolgateway.fn;

import com.hayden.commitdiffmodel.codegen.client.ExecuteGraphQLQuery;
import com.hayden.commitdiffmodel.codegen.types.CodeExecutionOptions;
import com.hayden.commitdiffmodel.codegen.types.CodeExecutionResult;
import com.hayden.commitdiffmodel.codegen.types.Error;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.client.DgsGraphQlClient;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FunctionCallingGraphqlRedeploy implements RedeployFunction {

    private final DgsGraphQlClient graphQlClient;

    @Override
    public RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name) {
        var execute = ExecuteGraphQLQuery.newRequest()
                .options(CodeExecutionOptions.newBuilder().build())
                .queryName("execute")
                .build();
        return from(
                graphQlClient.request(execute)
                        .retrieveSync()
                        .toEntity(CodeExecutionResult.class));
    }

    public RedeployDescriptor from(CodeExecutionResult result) {
        return new RedeployDescriptor(true, String.join(", ", result.getError().stream().map(Error::toString).toList()));
    }
}

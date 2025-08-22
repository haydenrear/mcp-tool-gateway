package com.hayden.mcptoolgateway.fn;

import com.hayden.commitdiffmodel.codegen.client.BuildGraphQLQuery;
import com.hayden.commitdiffmodel.codegen.client.BuildProjectionRoot;
import com.hayden.commitdiffmodel.codegen.client.ExecuteGraphQLQuery;
import com.hayden.commitdiffmodel.codegen.types.*;
import com.hayden.commitdiffmodel.codegen.types.Error;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.utilitymodule.stream.StreamUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.client.DgsGraphQlClient;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.file.Paths;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FunctionCallingGraphqlRedeploy implements RedeployFunction {

    private final DgsGraphQlClient graphQlClient;

    @Override
    public RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name) {
        var execute = BuildGraphQLQuery.newRequest()
                .options(CodeBuildOptions.newBuilder()
                        .writeToFile(true)
                        .build())
                .queryName("build")
                .build();
        return from(
                graphQlClient.request(execute)
                        .projection(new BuildProjectionRoot<>()
                                .artifactPaths()
                                .buildId()
                                .error()
                                    .message()
                                    .parent()
                                .success()
                                .sessionId()
                                .artifactOutputDirectory()
                                .executionTime()
                                .exitCode()
                                .registrationId()
                                .output()
                                .buildLog()
                        )
                        .retrieveSync()
                        .toEntity(CodeBuildResult.class));
    }

    public RedeployDescriptor from(CodeBuildResult result) {
        return Optional.ofNullable(result)
                .map(rd -> RedeployDescriptor.builder()
                        .err(CollectionUtils.isEmpty(result.getError())
                                ? null
                                : String.join(", ", StreamUtil.toStream(result.getError()).map(Error::toString).toList()))
                        .log(Paths.get(rd.getBuildLog()))
                        .isSuccess(result.getSuccess())
                        .exitCode(rd.getExitCode())
                        .binary(CollectionUtils.isEmpty(result.getArtifactPaths())
                                ? null
                                : Paths.get(result.getArtifactPaths().getFirst()))
                        .build())
                .orElseGet(() -> RedeployDescriptor.builder()
                        .err("Code build result was null!")
                        .build());
    }
}

package com.hayden.mcptoolgateway.fn;

import com.hayden.commitdiffmodel.codegen.client.*;
import com.hayden.commitdiffmodel.codegen.types.*;
import com.hayden.commitdiffmodel.codegen.types.Error;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.utilitymodule.stream.StreamUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.client.DgsGraphQlClient;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FunctionCallingGraphqlRedeploy implements RedeployFunction {

    private final DgsGraphQlClient graphQlClient;


    public void register(ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer) {
        RegisterCodeBuildGraphQLQuery registerCodeBuildGraphQLQuery = RegisterCodeBuildGraphQLQuery
                .newRequest()
                .codeBuildRegistration(
                        CodeBuildRegistrationIn.newBuilder()
                                .buildCommand(deployableMcpServer.getCommand())
                                .arguments(deployableMcpServer.getArguments())
                                .enabled(true)
                                .buildFailurePatterns(new ArrayList<>(deployableMcpServer.getFailurePatterns()))
                                .buildSuccessPatterns(new ArrayList<>(deployableMcpServer.getSuccessPatterns()))
                                .registrationId(deployableMcpServer.getName())
                                .executionType(ExecutionType.PROCESS_BUILDER)
                                .artifactOutputDirectory(deployableMcpServer.getCopyToArtifactPath().getParent().toString())
                                .workingDirectory(deployableMcpServer.getDirectory().toString())
                                .artifactPaths(List.of(deployableMcpServer.getCopyFromArtifactPath().toString()))
                                .build())
                .queryName("registerCodeBuild")
                .build();
        graphQlClient.request(registerCodeBuildGraphQLQuery)
                .projection(
                        new RegisterCodeBuildProjectionRoot<>()
                                .artifactPaths()
                )
                .retrieveSync()
                .toEntity(CodeBuildRegistration.class);

    }

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

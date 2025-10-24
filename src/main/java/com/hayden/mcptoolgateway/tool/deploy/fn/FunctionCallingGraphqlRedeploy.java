package com.hayden.mcptoolgateway.tool.deploy.fn;

import com.hayden.commitdiffmodel.codegen.client.*;
import com.hayden.commitdiffmodel.codegen.types.*;
import com.hayden.commitdiffmodel.codegen.types.Error;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.stream.StreamUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.DgsGraphQlClient;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "gateway.enable-redeployable", havingValue = "true", matchIfMissing = true)
public class FunctionCallingGraphqlRedeploy implements RedeployFunction {

    private final DgsGraphQlClient graphQlClient;


    public void register(ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer) {
        log.info("Registering {}", deployableMcpServer.getName());
        RegisterCodeBuildGraphQLQuery registerCodeBuildGraphQLQuery = RegisterCodeBuildGraphQLQuery
                .newRequest()
                .codeBuildRegistration(
                        CodeBuildRegistrationIn.newBuilder()
                                .buildCommand(deployableMcpServer.getCommand())
                                .arguments(deployableMcpServer.getArguments())
                                .enabled(true)
                                .buildFailurePatterns(StreamUtil.toStream(deployableMcpServer.getFailurePatterns()).toList())
                                .buildSuccessPatterns(StreamUtil.toStream(deployableMcpServer.getSuccessPatterns()).toList())
                                .registrationId(deployableMcpServer.getName())
                                .executionType(ExecutionType.PROCESS_BUILDER)
                                .artifactOutputDirectory(deployableMcpServer.getCopyToArtifactPath().toString())
                                .workingDirectory(deployableMcpServer.getDirectory().toString())
                                .artifactPaths(List.of(deployableMcpServer.getCopyFromArtifactPath().toString()))
                                .build())
                .queryName("registerCodeBuild")
                .build();

        var l = graphQlClient.request(registerCodeBuildGraphQLQuery)
                .projection(
                        new RegisterCodeBuildProjectionRoot<>()
                                .registrationId()
                                .artifactPaths()
                                .error()
                                .message()
                                .parent()
                )
                .retrieveSync()
                .toEntity(CodeBuildRegistration.class);

        log.info("Registered next build registration {}", l);

    }

    @Override
    public ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name) {
        var execute = BuildGraphQLQuery.newRequest()
                .options(CodeBuildOptions.newBuilder()
                        .writeToFile(true)
                        .registrationId(name.getName())
                        .build())
                .queryName("build")
                .build();
        log.info("Rebuilding {}", name);
        CodeBuildResult block = graphQlClient.request(execute)
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
                        .matchedOutput()
                        .buildLog()
                )
                .retrieveSync()
                .toEntity(CodeBuildResult.class);
        return from(block);
    }

    public ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor from(CodeBuildResult result) {
        log.info("Rebuilt {}", result);
        var r = Optional.ofNullable(result)
                .map(rd -> ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
                .orElseGet(() -> ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
                        .err("Code build result was null!")
                        .build());
        return r;
    }
}

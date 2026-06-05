package com.godsofdeath.monitor;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Entrypoint AWS Lambda.
 * Handler da configurare in Lambda/SAM: com.godsofdeath.monitor.StreamLambdaHandler::handleRequest
 */
public class StreamLambdaHandler implements RequestStreamHandler {

    private static final SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler;

    static {
        try {
            // Attiva il profilo "lambda": usa IAM role per DynamoDB e disabilita Swagger
            handler = SpringBootLambdaContainerHandler.getAwsProxyHandler(MonitorApplication.class, "lambda");
        } catch (ContainerInitializationException e) {
            throw new RuntimeException("Impossibile inizializzare il contesto Spring Boot su Lambda", e);
        }
    }

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        handler.proxyStream(inputStream, outputStream, context);
    }
}

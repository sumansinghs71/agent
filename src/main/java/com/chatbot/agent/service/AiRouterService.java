package com.chatbot.agent.service;


import com.chatbot.agent.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AiRouterService {

    @Value("${azure.openai.base-url}")
    private String azureOpenAiUrl;

    @Value("${azure.openai.api-key}")
    private String azureApiKey;

    @Value("${azure.openai.deployment-name}")
    private String deploymentName;

    @Value("${llama.base-url}")
    private String llamaUrl;

    private final RestTemplate restTemplate;

    public AiRouterService(@Qualifier("restTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String routeToAi(Model.ModelType modelType, String prompt) {
        if (modelType == Model.ModelType.AZURE_OPENAI) {
            return callAzureOpenAi(prompt);
        } else {
            return callLlama(prompt);
        }
    }

    private String callAzureOpenAi(String prompt) {
        String url = String.format("%s/openai/deployments/%s/completions?api-version=2021-04-30",
                azureOpenAiUrl, deploymentName);

        String requestBody = String.format("{\"prompt\": \"%s\", \"max_tokens\": 500}", prompt);

        // In production, use a proper DTO for request/response
        return restTemplate.postForObject(url, requestBody, String.class);
    }

    private String callLlama(String prompt) {
        String url = llamaUrl + "/generate";
        String requestBody = String.format("{\"text\": \"%s\"}", prompt);
        return restTemplate.postForObject(url, requestBody, String.class);
    }
}
/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.integration.tests.client.advisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.RelevancyEvaluator;
import org.springframework.ai.integration.tests.TestApplication;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.analysis.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RetrievalAugmentationAdvisor}.
 *
 * @author Thomas Vitale
 */
@SpringBootTest(classes = TestApplication.class)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".*")
class RetrievalAugmentationAdvisorIT {

	private List<Document> knowledgeBaseDocuments;

	@Autowired
	OpenAiChatModel openAiChatModel;

	@Autowired
	PgVectorStore pgVectorStore;

	@Value("${classpath:documents/knowledge-base.md}")
	Resource knowledgeBaseResource;

	@BeforeEach
	void setUp() {
		DocumentReader markdownReader = new MarkdownDocumentReader(knowledgeBaseResource,
				MarkdownDocumentReaderConfig.defaultConfig());
		knowledgeBaseDocuments = markdownReader.read();
		pgVectorStore.add(knowledgeBaseDocuments);
	}

	@AfterEach
	void tearDown() {
		pgVectorStore.delete(knowledgeBaseDocuments.stream().map(Document::getId).toList());
	}

	@Test
	void ragBasic() {
		String question = "Where does the adventure of Anacletus and Birba take place?";

		RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(pgVectorStore).build())
			.build();

		ChatResponse chatResponse = ChatClient.builder(openAiChatModel)
			.build()
			.prompt(question)
			.advisors(ragAdvisor)
			.call()
			.chatResponse();

		assertThat(chatResponse).isNotNull();

		String response = chatResponse.getResult().getOutput().getContent();
		System.out.println(response);
		assertThat(response).containsIgnoringCase("Highlands");

		evaluateRelevancy(question, chatResponse);
	}

	@Test
	void ragWithTranslation() {
		String question = "Hvor finder Anacletus og Birbas eventyr sted?";

		RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
			.queryTransformers(TranslationQueryTransformer.builder()
				.chatClientBuilder(ChatClient.builder(openAiChatModel))
				.targetLanguage("english")
				.build())
			.documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(pgVectorStore).build())
			.build();

		ChatResponse chatResponse = ChatClient.builder(openAiChatModel)
			.build()
			.prompt(question)
			.advisors(ragAdvisor)
			.call()
			.chatResponse();

		assertThat(chatResponse).isNotNull();

		String response = chatResponse.getResult().getOutput().getContent();
		System.out.println(response);
		assertThat(response).containsIgnoringCase("Highlands");

		evaluateRelevancy(question, chatResponse);
	}

	private void evaluateRelevancy(String question, ChatResponse chatResponse) {
		EvaluationRequest evaluationRequest = new EvaluationRequest(question,
				chatResponse.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT),
				chatResponse.getResult().getOutput().getContent());
		RelevancyEvaluator evaluator = new RelevancyEvaluator(ChatClient.builder(openAiChatModel));
		EvaluationResponse evaluationResponse = evaluator.evaluate(evaluationRequest);
		assertThat(evaluationResponse.isPass()).isTrue();
	}

}
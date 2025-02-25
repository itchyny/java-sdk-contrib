package dev.openfeature.contrib.providers.flipt;

import com.flipt.api.FliptApiClient;
import com.flipt.api.resources.evaluation.types.BooleanEvaluationResponse;
import com.flipt.api.resources.evaluation.types.EvaluationRequest;
import com.flipt.api.resources.evaluation.types.VariantEvaluationResponse;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.GeneralError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.openfeature.sdk.Reason.DEFAULT;
import static dev.openfeature.sdk.Reason.TARGETING_MATCH;

/**
 * Provider implementation for Flipt.
 */
@Slf4j
public class FliptProvider extends EventProvider {

    @Getter
    private static final String NAME = "Flipt";
    public static final String PROVIDER_NOT_YET_INITIALIZED = "provider not yet initialized";
    public static final String UNKNOWN_ERROR = "unknown error";

    @Getter(AccessLevel.PROTECTED)
    private FliptProviderConfig fliptProviderConfig;

    @Setter(AccessLevel.PROTECTED)
    @Getter
    private FliptApiClient fliptApiClient;

    @Setter(AccessLevel.PROTECTED)
    @Getter
    private ProviderState state = ProviderState.NOT_READY;

    private AtomicBoolean isInitialized = new AtomicBoolean(false);

    /**
     * Constructor.
     * @param fliptProviderConfig FliptProviderConfig
     */
    public FliptProvider(FliptProviderConfig fliptProviderConfig) {
        this.fliptProviderConfig = fliptProviderConfig;
    }

    /**
     * Initialize the provider.
     * @param evaluationContext evaluation context
     * @throws Exception on error
     */
    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        boolean initialized = isInitialized.getAndSet(true);
        if (initialized) {
            throw new GeneralError("already initialized");
        }
        super.initialize(evaluationContext);
        fliptApiClient = fliptProviderConfig.getFliptApiClientBuilder().build();

        state = ProviderState.READY;
        log.info("finished initializing provider, state: {}", state);
    }

    @Override
    public Metadata getMetadata() {
        return () -> NAME;
    }

    @Override
    public void emitProviderReady(ProviderEventDetails details) {
        super.emitProviderReady(details);
        state = ProviderState.READY;
    }

    @Override
    public void emitProviderError(ProviderEventDetails details) {
        super.emitProviderError(details);
        state = ProviderState.ERROR;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        if (!ProviderState.READY.equals(state)) {
            if (ProviderState.NOT_READY.equals(state)) {
                throw new ProviderNotReadyError(PROVIDER_NOT_YET_INITIALIZED);
            }
            throw new GeneralError(UNKNOWN_ERROR);
        }

        Map<String, String> contextMap = ContextTransformer.transform(ctx);
        EvaluationRequest request = EvaluationRequest.builder().namespaceKey(fliptProviderConfig.getNamespace())
            .flagKey(key).entityId(ctx.getTargetingKey()).context(contextMap).build();

        BooleanEvaluationResponse response = null;
        try {
            response = fliptApiClient.evaluation().boolean_(request);
        } catch (Exception e) {
            log.error("Error evaluating boolean", e);
            throw new GeneralError(e.getMessage());
        }

        return ProviderEvaluation.<Boolean>builder()
            .value(response.getEnabled())
            .reason(response.getReason().toString())
            .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> valueProviderEvaluation = getObjectEvaluation(key, new Value(defaultValue), ctx);
        return ProviderEvaluation.<String>builder()
            .value(valueProviderEvaluation.getValue().asString())
            .variant(valueProviderEvaluation.getVariant())
                .errorCode(valueProviderEvaluation.getErrorCode())
                .reason(valueProviderEvaluation.getReason())
                .flagMetadata(valueProviderEvaluation.getFlagMetadata())
            .build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> valueProviderEvaluation = getObjectEvaluation(key, new Value(defaultValue), ctx);
        Integer value = getIntegerValue(valueProviderEvaluation, defaultValue);
        return ProviderEvaluation.<Integer>builder()
            .value(value)
            .variant(valueProviderEvaluation.getVariant())
            .errorCode(valueProviderEvaluation.getErrorCode())
            .reason(valueProviderEvaluation.getReason())
            .flagMetadata(valueProviderEvaluation.getFlagMetadata())
            .build();
    }

    private static Integer getIntegerValue(ProviderEvaluation<Value> valueProviderEvaluation, Integer defaultValue) {
        String valueStr = valueProviderEvaluation.getValue().asObject().toString();
        try {
            return Integer.parseInt(valueStr);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> valueProviderEvaluation = getObjectEvaluation(key, new Value(defaultValue), ctx);
        Double value = getDoubleValue(valueProviderEvaluation, defaultValue);
        return ProviderEvaluation.<Double>builder()
            .value(value)
            .variant(valueProviderEvaluation.getVariant())
            .errorCode(valueProviderEvaluation.getErrorCode())
            .reason(valueProviderEvaluation.getReason())
            .flagMetadata(valueProviderEvaluation.getFlagMetadata())
            .build();
    }

    private static Double getDoubleValue(ProviderEvaluation<Value> valueProviderEvaluation, Double defaultValue) {
        String valueStr = valueProviderEvaluation.getValue().asObject().toString();
        try {
            return Double.parseDouble(valueStr);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        if (!ProviderState.READY.equals(state)) {
            if (ProviderState.NOT_READY.equals(state)) {
                throw new ProviderNotReadyError(PROVIDER_NOT_YET_INITIALIZED);
            }
            throw new GeneralError(UNKNOWN_ERROR);
        }
        Map<String, String> contextMap = ContextTransformer.transform(ctx);
        EvaluationRequest request = EvaluationRequest.builder().namespaceKey(fliptProviderConfig.getNamespace())
            .flagKey(key).entityId(ctx.getTargetingKey()).context(contextMap).build();

        VariantEvaluationResponse response;
        try {
            response = fliptApiClient.evaluation().variant(request);
        } catch (Exception e) {
            log.error("Error evaluating variant", e);
            throw new GeneralError(e.getMessage());
        }

        if (!response.getMatch()) {
            log.debug("non matching variant for {} : {}", key, response.getReason());
            return ProviderEvaluation.<Value>builder()
                .value(defaultValue)
                .reason(DEFAULT.name())
                .build();
        }

        ImmutableMetadata.ImmutableMetadataBuilder flagMetadataBuilder = ImmutableMetadata.builder();
        if (response.getVariantAttachment() != null) {
            flagMetadataBuilder.addString("variant-attachment", response.getVariantAttachment());
        }

        return ProviderEvaluation.<Value>builder()
            .value(new Value(response.getVariantKey()))
            .variant(response.getVariantKey())
            .reason(TARGETING_MATCH.name())
            .flagMetadata(flagMetadataBuilder.build())
            .build();
    }

    @Override
    public void shutdown() {
        super.shutdown();
        log.info("shutdown");
        state = ProviderState.NOT_READY;
    }
}

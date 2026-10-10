package com.a1.notify.provider.aws;

import com.a1.notify.provider.ProviderResult;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/** Maps AWS SDK failures to transient (retry) or permanent (do not retry) results. */
public final class AwsErrorClassifier {

    private AwsErrorClassifier() {
    }

    public static ProviderResult classify(RuntimeException ex) {
        if (ex instanceof AwsServiceException service) {
            String code = service.awsErrorDetails() != null && service.awsErrorDetails().errorCode() != null
                    ? service.awsErrorDetails().errorCode()
                    : ex.getClass().getSimpleName();
            boolean transientError = service.isThrottlingException() || service.statusCode() >= 500
                    || "LimitExceededException".equals(code) || "TooManyRequestsException".equals(code);
            return transientError ? ProviderResult.transientFailure(code) : ProviderResult.permanentFailure(code);
        }
        if (ex instanceof SdkClientException) {
            return ProviderResult.transientFailure("SDK_CLIENT_ERROR");
        }
        return ProviderResult.permanentFailure(ex.getClass().getSimpleName());
    }
}

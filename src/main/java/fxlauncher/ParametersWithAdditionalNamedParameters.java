package fxlauncher;

import javafx.application.Application;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParametersWithAdditionalNamedParameters extends Application.Parameters {

    private Application.Parameters delegate;
    private Map<String, String> combinedNamedParameters;

    public ParametersWithAdditionalNamedParameters(Application.Parameters delegate, Map<String, String> additionalNamedParameters) {
        this.delegate = delegate;
        this.combinedNamedParameters = combineNamedParameters(delegate, additionalNamedParameters);
    }

    private Map<String, String> combineNamedParameters(Application.Parameters delegate, Map<String, String> additionalNamedParameters) {
        Map<String, String> combinedNamedParameters = new HashMap<>();
        combinedNamedParameters.putAll(delegate.getNamed());
        combinedNamedParameters.putAll(additionalNamedParameters);
        return Collections.unmodifiableMap(combinedNamedParameters);
    }

    @Override
    public List<String> getRaw() {
        return delegate.getRaw();
    }

    @Override
    public List<String> getUnnamed() {
        return delegate.getUnnamed();
    }

    @Override
    public Map<String, String> getNamed() {
        return combinedNamedParameters;
    }
}

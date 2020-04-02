package org.jobrunr.dashboard.server.http.url;

import org.jobrunr.utils.reflection.ReflectionUtils;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class TeenyRequestUrl {
    private final String url;
    private final Map<String, String> params;
    private final Map<String, List<String>> queryParams;

    public TeenyRequestUrl(String url, Map<String, String> params) {
        this.url = url;
        this.params = params;
        this.queryParams = initQueryParams(url);
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public String param(String paramName) {
        return params.get(paramName);
    }

    public <T> T param(String paramName, Class<T> clazz) {
        if(UUID.class.equals(clazz)) {
            return (T) UUID.fromString(param(paramName));
        } else if (clazz.isEnum()) {
            return Stream.of(clazz.getEnumConstants())
                    .filter(val -> ((Enum)val).name().equalsIgnoreCase(param(paramName)))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No enum constant " + clazz.getCanonicalName() + "." + param(paramName)));
        }
        throw new IllegalArgumentException(paramName);
    }

    public String queryParam(String queryParamName) {
        return optionalQueryParam(queryParamName).orElse(null);
    }

    public int queryParam(String queryParamName, int defaultValue) {
        return optionalQueryParam(queryParamName)
                .map(Integer::parseInt)
                .orElse(defaultValue);
    }

    public <T> T fromQueryParams(Class<T> clazz) {
        Map<String, String> fieldValues = queryParams.entrySet().stream()
                .collect(toMap(Entry::getKey, e -> e.getValue().get(0)));

        return ReflectionUtils.newInstance(clazz, fieldValues);
    }

    private Map<String, List<String>> initQueryParams(String url) {
        if (!url.contains("?")) return Collections.emptyMap();

        return Arrays.stream(url.substring(url.indexOf('?') + 1).split("&"))
                .map(this::splitQueryParameter)
                .collect(groupingBy(SimpleImmutableEntry::getKey, LinkedHashMap::new, mapping(Entry::getValue, toList())));
    }

    private SimpleImmutableEntry<String, String> splitQueryParameter(String it) {
        final int idx = it.indexOf('=');
        final String key = idx > 0 ? it.substring(0, idx) : it;
        final String value = idx > 0 && it.length() > idx + 1 ? it.substring(idx + 1) : null;
        return new SimpleImmutableEntry<>(key, value);
    }

    private Optional<String> optionalQueryParam(String queryParamName) {
        if (!queryParams.containsKey(queryParamName)) return Optional.empty();
        return Optional.ofNullable(queryParams.get(queryParamName).get(0));
    }
}

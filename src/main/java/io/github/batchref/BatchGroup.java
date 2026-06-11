package io.github.batchref;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BatchGroup {

    private final BatchQuery<?> sampleQuery;
    private final Set<Object> keys = new LinkedHashSet<>();
    private final List<BatchRef<?>> refs = new ArrayList<>();

    BatchGroup(BatchQuery<?> sampleQuery) {
        this.sampleQuery = sampleQuery;
    }

    void add(BatchRegistration<?> registration) {
        keys.add(registration.query().key());
        refs.add(registration.ref());
    }

    void loadAndReplay() {
        Map<Object, ?> loadedMap = load(keys);
        for (BatchRef<?> ref : refs) {
            ref.replay(loadedMap.get(ref.key()));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Object, ?> load(Collection<Object> keysToLoad) {
        Map<Object, ?> loadedMap = (Map<Object, ?>) ((BatchQuery) sampleQuery)
                .batchLoader()
                .apply(Collections.unmodifiableCollection(keysToLoad));
        if (loadedMap == null) {
            return Collections.emptyMap();
        }
        return loadedMap;
    }
}

package io.github.batchref;

import io.github.batchref.annotation.BatchQueryMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRefTest {

    @Test
    void fallbackLoaderRunsWithoutScopeAndStepsApplyImmediately() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        Holder holder = new Holder();

        BatchRef<Row> ref = BatchRef.wrap(BatchQuery.of(
                "row.byId",
                1L,
                keys -> {
                    batchCalls.incrementAndGet();
                    return Map.<Object, Row>of();
                },
                () -> {
                    fallbackCalls.incrementAndGet();
                    return new Row(1L, "one", 1);
                }
        ));

        ref.whenPresent(row -> {
                    holder.present = true;
                    holder.rowId = row.id();
                })
                .setOut(value -> holder.name = value, Row::name)
                .whenValue(Row::status, status -> status == 1, () -> holder.enabled = true);

        assertThat(fallbackCalls).hasValue(1);
        assertThat(batchCalls).hasValue(0);
        assertThat(holder.present).isTrue();
        assertThat(holder.rowId).isEqualTo(1L);
        assertThat(holder.name).isEqualTo("one");
        assertThat(holder.enabled).isTrue();
        assertThat(ref.isResolved()).isTrue();
    }

    @Test
    void publicApiDoesNotExposeImmediateGetter() {
        assertThat(Arrays.stream(BatchRef.class.getMethods())
                .filter(method -> method.getDeclaringClass() == BatchRef.class)
                .map(Method::getName))
                .doesNotContain("get", "unsafeGet", "setIn");
    }

    @Test
    void flushGroupsRefsByLoaderNameAndReplaysInRegistrationOrder() {
        AtomicInteger batchCalls = new AtomicInteger();
        Holder first = new Holder();
        Holder second = new Holder();
        Holder repeated = new Holder();

        BatchRefs.runInScope(() -> {
            ref(1L, batchCalls).setOut(value -> first.name = value, Row::name);
            ref(2L, batchCalls).setOut(second::setName).from(Row::name);
            ref(1L, batchCalls).setOut(value -> repeated.name = value, Row::name);

            assertThat(first.name).isNull();
            BatchRefs.flush();

            assertThat(first.name).isEqualTo("row-1");
            assertThat(second.name).isEqualTo("row-2");
            assertThat(repeated.name).isEqualTo("row-1");
        });

        assertThat(batchCalls).hasValue(1);
    }

    @Test
    void absentStepsAndDefaultValuesRunWhenValueIsMissing() {
        Holder holder = new Holder();

        BatchRefs.runInScope(() -> {
            BatchRef.wrap(BatchQuery.of(
                    "row.missing",
                    404L,
                    keys -> Map.<Object, Row>of(),
                    () -> null
            )).whenAbsent(() -> holder.present = false)
                    .setOut(holder::setName)
                    .fromOrDefault(Row::name, "missing");

            BatchRefs.flush();
        });

        assertThat(holder.present).isFalse();
        assertThat(holder.name).isEqualTo("missing");
    }

    @Test
    void stepsAddedAfterFlushUseResolvedValueImmediately() {
        Holder holder = new Holder();
        BatchRef<Row> ref = BatchRefs.runInScope(() -> {
            BatchRef<Row> scopedRef = ref(7L, new AtomicInteger());
            BatchRefs.flush();
            return scopedRef;
        });

        ref.setOut(holder::setName).from(Row::name);

        assertThat(holder.name).isEqualTo("row-7");
    }

    @Test
    void annotatedMethodReferenceRegistersBatchQueryWithoutAopProxy() {
        DirectRowQueries queries = new DirectRowQueries();
        Holder first = new Holder();
        Holder second = new Holder();

        BatchRefs.runInScope(() -> {
            BatchRef.wrap(queries::rowById, 1L)
                    .setOut(first::setName)
                    .from(Row::name);
            BatchRef.wrap(queries::rowById, 2L)
                    .setOut(second::setName)
                    .from(Row::name);

            assertThat(queries.fallbackCalls).hasValue(0);
            assertThat(queries.batchCalls).hasValue(0);
        });

        assertThat(first.name).isEqualTo("row-1");
        assertThat(second.name).isEqualTo("row-2");
        assertThat(queries.fallbackCalls).hasValue(0);
        assertThat(queries.batchCalls).hasValue(1);
    }

    @Test
    void nestedWhenCallbacksCanRegisterBatchRefsForNextFlushWave() {
        AtomicInteger parentBatchCalls = new AtomicInteger();
        AtomicInteger presentChildBatchCalls = new AtomicInteger();
        AtomicInteger absentChildBatchCalls = new AtomicInteger();
        AtomicInteger valueChildBatchCalls = new AtomicInteger();
        Holder presentChild = new Holder();
        Holder absentChild = new Holder();
        Holder valueChild = new Holder();

        BatchRefs.runInScope(() -> {
            BatchRef.wrap(rowQuery(
                    "nested.parent.present",
                    1L,
                    Map.of(1L, new Row(1L, "parent-present", 1)),
                    parentBatchCalls
            )).whenPresent(parent -> BatchRef.wrap(rowQuery(
                    "nested.child.present",
                    parent.id() + 10,
                    Map.of(11L, new Row(11L, "present-child", 1)),
                    presentChildBatchCalls
            )).setOut(presentChild::setName).from(Row::name));

            BatchRef.wrap(rowQuery(
                    "nested.parent.absent",
                    2L,
                    Map.of(),
                    parentBatchCalls
            )).whenAbsent(() -> BatchRef.wrap(rowQuery(
                    "nested.child.absent",
                    22L,
                    Map.of(22L, new Row(22L, "absent-child", 1)),
                    absentChildBatchCalls
            )).setOut(absentChild::setName).from(Row::name));

            BatchRef.wrap(rowQuery(
                    "nested.parent.value",
                    3L,
                    Map.of(3L, new Row(3L, "parent-value", 1)),
                    parentBatchCalls
            )).whenValue(
                    Row::status,
                    status -> status == 1,
                    () -> BatchRef.wrap(rowQuery(
                            "nested.child.value",
                            33L,
                            Map.of(33L, new Row(33L, "value-child", 1)),
                            valueChildBatchCalls
                    )).setOut(valueChild::setName).from(Row::name)
            );

            assertThat(presentChild.name).isNull();
            assertThat(absentChild.name).isNull();
            assertThat(valueChild.name).isNull();
        });

        assertThat(presentChild.name).isEqualTo("present-child");
        assertThat(absentChild.name).isEqualTo("absent-child");
        assertThat(valueChild.name).isEqualTo("value-child");
        assertThat(parentBatchCalls).hasValue(3);
        assertThat(presentChildBatchCalls).hasValue(1);
        assertThat(absentChildBatchCalls).hasValue(1);
        assertThat(valueChildBatchCalls).hasValue(1);
    }

    @Test
    void fillGcInfoRegistersStepsAndAutoFlushReplaysThemWithoutExposingEntity() {
        AtomicInteger relationBatchCalls = new AtomicInteger();
        AtomicInteger relationFallbackCalls = new AtomicInteger();
        AtomicInteger userBatchCalls = new AtomicInteger();
        AtomicInteger userFallbackCalls = new AtomicInteger();
        ProjectVO project = new ProjectVO(10L);
        ProjectListParam param = new ProjectListParam(20L);

        BatchRefs.runInScope(() -> {
            BatchRef<GeneralContractingProjectGroupRelation> relationRef = BatchRef.wrap(relationQuery(
                    project.getProjectId(),
                    Map.of(10L, new GeneralContractingProjectGroupRelation(100L, 200L, true, 1)),
                    relationBatchCalls,
                    relationFallbackCalls
            ));
            BatchRef<GeneralContractingProjectUser> gcUserRef = BatchRef.wrap(gcUserQuery(
                    project.getProjectId(),
                    param.getUserId(),
                    Map.of(new WorkerUserKey(10L, 20L), new GeneralContractingProjectUser(300L, 1)),
                    userBatchCalls,
                    userFallbackCalls
            ));

            fillGcInfo(project, relationRef, gcUserRef);

            assertThat(project.getRelatedToGc()).isNull();
            assertThat(project.getGcProjectId()).isNull();
            assertThat(project.getJoinedGcProject()).isNull();
        });

        assertThat(relationBatchCalls).hasValue(1);
        assertThat(relationFallbackCalls).hasValue(0);
        assertThat(userBatchCalls).hasValue(1);
        assertThat(userFallbackCalls).hasValue(0);
        assertThat(project.getRelatedToGc()).isTrue();
        assertThat(project.getGcProjectId()).isEqualTo(100L);
        assertThat(project.getGcRelationId()).isEqualTo(200L);
        assertThat(project.getNeedGcApproval()).isTrue();
        assertThat(project.getGcRelationStatusName()).isEqualTo("正常");
        assertThat(project.getJoinedGcProject()).isTrue();
        assertThat(project.getGcUserId()).isEqualTo(300L);
        assertThat(project.getGcUserType()).isEqualTo(1);
        assertThat(project.getGcUserTypeName()).isEqualTo("正式工");
    }

    @Test
    void fillGcInfoRunsAbsentStepsWhenBatchResultIsMissing() {
        ProjectVO project = new ProjectVO(404L);
        project.setRelatedToGc(true);
        project.setGcProjectId(100L);
        project.setGcRelationId(200L);
        project.setNeedGcApproval(true);
        project.setGcRelationStatusName("正常");
        project.setJoinedGcProject(true);
        project.setGcUserId(300L);
        project.setGcUserType(1);
        project.setGcUserTypeName("正式工");

        BatchRefs.runInScope(() -> {
            BatchRef<GeneralContractingProjectGroupRelation> relationRef = BatchRef.wrap(relationQuery(
                    project.getProjectId(),
                    Map.of(),
                    new AtomicInteger(),
                    new AtomicInteger()
            ));
            BatchRef<GeneralContractingProjectUser> gcUserRef = BatchRef.wrap(gcUserQuery(
                    project.getProjectId(),
                    20L,
                    Map.of(),
                    new AtomicInteger(),
                    new AtomicInteger()
            ));

            fillGcInfo(project, relationRef, gcUserRef);
        });

        assertThat(project.getRelatedToGc()).isFalse();
        assertThat(project.getGcProjectId()).isNull();
        assertThat(project.getGcRelationId()).isNull();
        assertThat(project.getNeedGcApproval()).isFalse();
        assertThat(project.getGcRelationStatusName()).isNull();
        assertThat(project.getJoinedGcProject()).isFalse();
        assertThat(project.getGcUserId()).isNull();
        assertThat(project.getGcUserType()).isNull();
        assertThat(project.getGcUserTypeName()).isNull();
    }

    private static BatchRef<Row> ref(Long id, AtomicInteger batchCalls) {
        return BatchRef.wrap(BatchQuery.of(
                "row.byId",
                id,
                keys -> {
                    batchCalls.incrementAndGet();
                    return rows(keys);
                },
                () -> new Row(id, "fallback-" + id, 1)
        ));
    }

    private static BatchQuery<Row> rowQuery(
            String loaderName,
            Long id,
            Map<Long, Row> values,
            AtomicInteger batchCalls
    ) {
        return BatchQuery.ofTyped(
                loaderName,
                id,
                keys -> {
                    batchCalls.incrementAndGet();
                    Map<Long, Row> rows = new LinkedHashMap<>();
                    for (Long key : keys) {
                        if (values.containsKey(key)) {
                            rows.put(key, values.get(key));
                        }
                    }
                    return rows;
                },
                () -> values.get(id)
        );
    }

    private static Map<Object, Row> rows(Collection<Object> keys) {
        Map<Object, Row> rows = new LinkedHashMap<>();
        assertThat(keys).containsExactlyElementsOf(List.copyOf(keys));
        for (Object key : keys) {
            Long id = (Long) key;
            rows.put(id, new Row(id, "row-" + id, 1));
        }
        return rows;
    }

    private static BatchQuery<GeneralContractingProjectGroupRelation> relationQuery(
            Long workerProjectId,
            Map<Long, GeneralContractingProjectGroupRelation> values,
            AtomicInteger batchCalls,
            AtomicInteger fallbackCalls
    ) {
        return BatchQuery.ofTyped(
                "relation.activeByWorkerProjectId",
                workerProjectId,
                keys -> {
                    batchCalls.incrementAndGet();
                    Map<Long, GeneralContractingProjectGroupRelation> loaded = new LinkedHashMap<>();
                    for (Long key : keys) {
                        if (values.containsKey(key)) {
                            loaded.put(key, values.get(key));
                        }
                    }
                    return loaded;
                },
                () -> {
                    fallbackCalls.incrementAndGet();
                    return values.get(workerProjectId);
                }
        );
    }

    private static BatchQuery<GeneralContractingProjectUser> gcUserQuery(
            Long workerProjectId,
            Long userId,
            Map<WorkerUserKey, GeneralContractingProjectUser> values,
            AtomicInteger batchCalls,
            AtomicInteger fallbackCalls
    ) {
        WorkerUserKey key = new WorkerUserKey(workerProjectId, userId);
        return BatchQuery.ofTyped(
                "gcUser.activeByWorkerProjectIdAndUserId",
                key,
                keys -> {
                    batchCalls.incrementAndGet();
                    Map<WorkerUserKey, GeneralContractingProjectUser> loaded = new LinkedHashMap<>();
                    for (WorkerUserKey currentKey : keys) {
                        if (values.containsKey(currentKey)) {
                            loaded.put(currentKey, values.get(currentKey));
                        }
                    }
                    return loaded;
                },
                () -> {
                    fallbackCalls.incrementAndGet();
                    return values.get(key);
                }
        );
    }

    private static void fillGcInfo(
            ProjectVO project,
            BatchRef<GeneralContractingProjectGroupRelation> relationRef,
            BatchRef<GeneralContractingProjectUser> gcUserRef
    ) {
        relationRef.whenAbsent(() -> {
            project.setRelatedToGc(false);
            project.setGcProjectId(null);
            project.setGcRelationId(null);
            project.setNeedGcApproval(false);
            project.setGcRelationStatusName(null);

            project.setJoinedGcProject(false);
            project.setGcUserId(null);
            project.setGcUserType(null);
            project.setGcUserTypeName(null);
        });

        relationRef.whenPresent(relation -> project.setRelatedToGc(relation.getId() != null));

        relationRef.setOut(
                project::setGcProjectId,
                GeneralContractingProjectGroupRelation::getGcProjectId
        );

        relationRef.setOut(project::setGcRelationId)
                .from(GeneralContractingProjectGroupRelation::getId);

        relationRef.setOut(project::setNeedGcApproval)
                .fromMapped(
                        GeneralContractingProjectGroupRelation::getNeedApproval,
                        Boolean.TRUE::equals
                );

        relationRef.setOutMapped(
                project::setGcRelationStatusName,
                GeneralContractingProjectGroupRelation::getStatus,
                status -> Objects.equals(status, 1) ? "正常" : "已停用"
        );

        gcUserRef.whenAbsent(() -> {
            project.setJoinedGcProject(false);
            project.setGcUserId(null);
            project.setGcUserType(null);
            project.setGcUserTypeName(null);
        });

        gcUserRef.whenPresent(gcUser -> project.setJoinedGcProject(gcUser.getId() != null));

        gcUserRef.setOut(
                project::setGcUserId,
                GeneralContractingProjectUser::getId
        );

        gcUserRef.setOut(project::setGcUserType)
                .from(GeneralContractingProjectUser::getUserType);

        gcUserRef.setOut(project::setGcUserTypeName)
                .fromMapped(
                        GeneralContractingProjectUser::getUserType,
                        BatchRefTest::toGcUserTypeName
                );
    }

    private static String toGcUserTypeName(Integer userType) {
        if (Objects.equals(userType, 1)) {
            return "正式工";
        }

        if (Objects.equals(userType, 2)) {
            return "临时工";
        }

        return "未知";
    }

    private record Row(Long id, String name, Integer status) {
    }

    private static final class DirectRowQueries {

        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final AtomicInteger batchCalls = new AtomicInteger();

        @BatchQueryMethod(batchMethod = "rowsByIds")
        private Row rowById(Long id) {
            fallbackCalls.incrementAndGet();
            return new Row(id, "fallback-" + id, 1);
        }

        private Map<Long, Row> rowsByIds(Collection<Long> ids) {
            batchCalls.incrementAndGet();
            Map<Long, Row> rows = new LinkedHashMap<>();
            for (Long id : ids) {
                rows.put(id, new Row(id, "row-" + id, 1));
            }
            return rows;
        }
    }

    private record ProjectListParam(Long userId) {

        private Long getUserId() {
            return userId;
        }
    }

    private record WorkerUserKey(Long workerProjectId, Long userId) {
    }

    private static final class GeneralContractingProjectGroupRelation {
        private final Long gcProjectId;
        private final Long id;
        private final Boolean needApproval;
        private final Integer status;

        private GeneralContractingProjectGroupRelation(
                Long gcProjectId,
                Long id,
                Boolean needApproval,
                Integer status
        ) {
            this.gcProjectId = gcProjectId;
            this.id = id;
            this.needApproval = needApproval;
            this.status = status;
        }

        private Long getGcProjectId() {
            return gcProjectId;
        }

        private Long getId() {
            return id;
        }

        private Boolean getNeedApproval() {
            return needApproval;
        }

        private Integer getStatus() {
            return status;
        }
    }

    private static final class GeneralContractingProjectUser {
        private final Long id;
        private final Integer userType;

        private GeneralContractingProjectUser(Long id, Integer userType) {
            this.id = id;
            this.userType = userType;
        }

        private Long getId() {
            return id;
        }

        private Integer getUserType() {
            return userType;
        }
    }

    private static final class ProjectVO {
        private final Long projectId;
        private Boolean relatedToGc;
        private Long gcProjectId;
        private Long gcRelationId;
        private Boolean needGcApproval;
        private String gcRelationStatusName;
        private Boolean joinedGcProject;
        private Long gcUserId;
        private Integer gcUserType;
        private String gcUserTypeName;

        private ProjectVO(Long projectId) {
            this.projectId = projectId;
        }

        private Long getProjectId() {
            return projectId;
        }

        private Boolean getRelatedToGc() {
            return relatedToGc;
        }

        private void setRelatedToGc(Boolean relatedToGc) {
            this.relatedToGc = relatedToGc;
        }

        private Long getGcProjectId() {
            return gcProjectId;
        }

        private void setGcProjectId(Long gcProjectId) {
            this.gcProjectId = gcProjectId;
        }

        private Long getGcRelationId() {
            return gcRelationId;
        }

        private void setGcRelationId(Long gcRelationId) {
            this.gcRelationId = gcRelationId;
        }

        private Boolean getNeedGcApproval() {
            return needGcApproval;
        }

        private void setNeedGcApproval(Boolean needGcApproval) {
            this.needGcApproval = needGcApproval;
        }

        private String getGcRelationStatusName() {
            return gcRelationStatusName;
        }

        private void setGcRelationStatusName(String gcRelationStatusName) {
            this.gcRelationStatusName = gcRelationStatusName;
        }

        private Boolean getJoinedGcProject() {
            return joinedGcProject;
        }

        private void setJoinedGcProject(Boolean joinedGcProject) {
            this.joinedGcProject = joinedGcProject;
        }

        private Long getGcUserId() {
            return gcUserId;
        }

        private void setGcUserId(Long gcUserId) {
            this.gcUserId = gcUserId;
        }

        private Integer getGcUserType() {
            return gcUserType;
        }

        private void setGcUserType(Integer gcUserType) {
            this.gcUserType = gcUserType;
        }

        private String getGcUserTypeName() {
            return gcUserTypeName;
        }

        private void setGcUserTypeName(String gcUserTypeName) {
            this.gcUserTypeName = gcUserTypeName;
        }
    }

    private static final class Holder {
        private boolean present;
        private boolean enabled;
        private Long rowId;
        private String name;

        private void setName(String name) {
            this.name = name;
        }
    }
}

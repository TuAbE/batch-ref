# MyBatis Plus 用法

如果 QueryService 已经有自己的批量方法，优先沿用文档里的手写 `BatchQuery.of(...)`：

```java
public BatchQuery<GeneralContractingProjectGroupRelation> activeRelationByWorkerProjectId(Long workerProjectId) {
    return BatchQuery.ofTyped(
            "projectGcRelation.activeByWorkerProjectId",
            workerProjectId,
            this::getActiveRelationMapByWorkerProjectIds,
            () -> getActiveRelationByWorkerProjectId(workerProjectId)
    );
}
```

批量方法仍然使用 MyBatis Plus：

```java
public Map<Long, GeneralContractingProjectGroupRelation> getActiveRelationMapByWorkerProjectIds(
        Collection<Long> workerProjectIds
) {
    if (workerProjectIds == null || workerProjectIds.isEmpty()) {
        return Collections.emptyMap();
    }

    List<GeneralContractingProjectGroupRelation> list = relationMapper.selectList(
            Wrappers.<GeneralContractingProjectGroupRelation>lambdaQuery()
                    .in(GeneralContractingProjectGroupRelation::getWorkerProjectId, workerProjectIds)
                    .eq(GeneralContractingProjectGroupRelation::getDeleted, false)
                    .eq(GeneralContractingProjectGroupRelation::getStatus, 1)
    );

    return list.stream().collect(Collectors.toMap(
            GeneralContractingProjectGroupRelation::getWorkerProjectId,
            Function.identity(),
            (a, b) -> a
    ));
}
```

如果想少写重复查询模板，可以用 `MybatisPlusBatchQueries.queryByKey(...)`：

```java
public BatchQuery<GeneralContractingProjectGroupRelation> activeRelationByWorkerProjectId(Long workerProjectId) {
    return MybatisPlusBatchQueries.queryByKey(
            "projectGcRelation.activeByWorkerProjectId",
            workerProjectId,
            relationMapper,
            Long.class,
            GeneralContractingProjectGroupRelation::getWorkerProjectId,
            GeneralContractingProjectGroupRelation::getWorkerProjectId,
            wrapper -> wrapper
                    .eq(GeneralContractingProjectGroupRelation::getDeleted, false)
                    .eq(GeneralContractingProjectGroupRelation::getStatus, 1)
    );
}
```

带固定参数时，`loaderName` 必须包含固定参数，避免不同条件被错误合并：

```java
public BatchQuery<GeneralContractingProjectUser> activeUserByWorkerProjectIdAndUserId(
        Long workerProjectId,
        Long userId
) {
    return MybatisPlusBatchQueries.queryByKey(
            "projectGcUser.activeByWorkerProjectIdAndUserId:userId=" + userId,
            workerProjectId,
            userMapper,
            Long.class,
            GeneralContractingProjectUser::getWorkerProjectId,
            GeneralContractingProjectUser::getWorkerProjectId,
            wrapper -> wrapper
                    .eq(GeneralContractingProjectUser::getUserId, userId)
                    .eq(GeneralContractingProjectUser::getDeleted, false)
    );
}
```

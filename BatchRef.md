# BatchRef

## 核心思想

```text
1. 业务代码里写 ref.setOut / ref.whenPresent / ref.whenValue
2. 这些方法不马上取值、不马上执行
3. 只是把“以后要做的动作”收集起来
4. 最后 BatchRefs.flush()
5. 统一批量查询
6. 查询完成后，按顺序回放所有动作
```

这样业务代码就不会直接：

```java
Relation relation = relationRef.get();
```

而是写成：

```java
relationRef.setOut(Relation::getName, project::setRelationName);
relationRef.whenValue(Relation::getStatus, status -> status == 1, () -> ...);
```

---

# 一、最终业务代码示例

```java
@BatchScope
public List<ProjectVO> getProjectList(ProjectListParam param) {

    List<ProjectVO> projectList = queryProjectList(param);

    for (ProjectVO project : projectList) {

        BatchRef<GeneralContractingProjectGroupRelation> relationRef =
                BatchRef.wrap(
                        projectGcRelationQueryService.activeRelationByWorkerProjectId(
                                project.getProjectId()
                        )
                );

        BatchRef<GeneralContractingProjectUser> gcUserRef =
                BatchRef.wrap(
                        projectGcUserQueryService.activeUserByWorkerProjectIdAndUserId(
                                project.getProjectId(),
                                param.getUserId()
                        )
                );

        fillGcInfo(project, relationRef, gcUserRef);
    }

    BatchRefs.flush();

    return projectList;
}
```

这里注意：

```java
fillGcInfo(project, relationRef, gcUserRef);
```

不是马上填充，而是**登记填充步骤**。

---

# 二、fillGcInfo 写法

```java
private void fillGcInfo(
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

    relationRef.whenPresent(() -> {
        project.setRelatedToGc(true);
    });

    relationRef.setOut(
            GeneralContractingProjectGroupRelation::getGcProjectId,
            project::setGcProjectId
    );

    relationRef.setOut(
            GeneralContractingProjectGroupRelation::getId,
            project::setGcRelationId
    );

    relationRef.setOutMapped(
            GeneralContractingProjectGroupRelation::getNeedApproval,
            Boolean.TRUE::equals,
            project::setNeedGcApproval
    );

    relationRef.setOutMapped(
            GeneralContractingProjectGroupRelation::getStatus,
            status -> Objects.equals(status, 1) ? "正常" : "已停用",
            project::setGcRelationStatusName
    );

    gcUserRef.whenAbsent(() -> {
        project.setJoinedGcProject(false);
        project.setGcUserId(null);
        project.setGcUserType(null);
        project.setGcUserTypeName(null);
    });

    gcUserRef.whenPresent(() -> {
        project.setJoinedGcProject(true);
    });

    gcUserRef.setOut(
            GeneralContractingProjectUser::getId,
            project::setGcUserId
    );

    gcUserRef.setOut(
            GeneralContractingProjectUser::getUserType,
            project::setGcUserType
    );

    gcUserRef.setOutMapped(
            GeneralContractingProjectUser::getUserType,
            this::toGcUserTypeName,
            project::setGcUserTypeName
    );
}
```

辅助方法：

```java
private String toGcUserTypeName(Integer userType) {
    if (Objects.equals(userType, 1)) {
        return "正式工";
    }

    if (Objects.equals(userType, 2)) {
        return "临时工";
    }

    return "未知";
}
```

这段代码的特点是：

```text
1. 没有 relationRef.get()
2. 没有把实体对象暴露出来
3. 所有读取属性、赋值、条件判断都通过 Function / Consumer / Predicate 输入
4. 执行时机统一交给 BatchRefs.flush()
```

---

# 三、你要的几种常用动作

## 1. 直接把内部字段设置到外部对象

```java
relationRef.setOut(
        GeneralContractingProjectGroupRelation::getGcProjectId,
        project::setGcProjectId
);
```

含义：

```text
等 relation 查出来后：
project.setGcProjectId(relation.getGcProjectId())
```

---

## 2. 内部字段转换后设置到外部对象

```java
relationRef.setOutMapped(
        GeneralContractingProjectGroupRelation::getStatus,
        status -> Objects.equals(status, 1) ? "正常" : "已停用",
        project::setGcRelationStatusName
);
```

含义：

```text
等 relation 查出来后：
status = relation.getStatus()
statusName = mapper.apply(status)
project.setGcRelationStatusName(statusName)
```

---

## 3. 存在时执行

```java
relationRef.whenPresent(() -> {
    project.setRelatedToGc(true);
});
```

含义：

```text
relation != null 时执行
```

---

## 4. 不存在时执行

```java
relationRef.whenAbsent(() -> {
    project.setRelatedToGc(false);
});
```

含义：

```text
relation == null 时执行
```

---

## 5. 字段满足条件时执行

```java
relationRef.whenValue(
        GeneralContractingProjectGroupRelation::getStatus,
        status -> Objects.equals(status, 1),
        () -> project.setGcRelationStatusName("正常")
);
```

含义：

```text
relation != null
并且 relation.getStatus() == 1
就执行 runner
```

---

## 6. 条件成立和不成立分别执行

```java
relationRef.whenValue(
        GeneralContractingProjectGroupRelation::getStatus,
        status -> Objects.equals(status, 1),
        () -> project.setGcRelationStatusName("正常"),
        () -> project.setGcRelationStatusName("已停用")
);
```

---

## 7. 没有值时给默认值

```java
relationRef.setOutOrDefault(
        GeneralContractingProjectGroupRelation::getName,
        project::setGcRelationName,
        "未关联总包"
);
```

含义：

```text
如果 relation 存在：
    project.setGcRelationName(relation.getName())

如果 relation 不存在：
    project.setGcRelationName("未关联总包")
```

---

# 四、BatchRef 的核心接口设计

```java
public class BatchRef<T> {

    public static <T> BatchRef<T> wrap(BatchQuery<T> query) {
        return BatchRefs.register(query);
    }

    public BatchRef<T> whenPresent(Runnable runner) {
        // 收集步骤
        return this;
    }

    public BatchRef<T> whenAbsent(Runnable runner) {
        // 收集步骤
        return this;
    }

    public <V> BatchRef<T> setOut(
            Function<T, V> getter,
            Consumer<V> setter
    ) {
        // 收集步骤
        return this;
    }

    public <V> BatchRef<T> setOutOrDefault(
            Function<T, V> getter,
            Consumer<V> setter,
            V defaultValue
    ) {
        // 收集步骤
        return this;
    }

    public <V, R> BatchRef<T> setOutMapped(
            Function<T, V> getter,
            Function<V, R> mapper,
            Consumer<R> setter
    ) {
        // 收集步骤
        return this;
    }

    public <V> BatchRef<T> whenValue(
            Function<T, V> getter,
            Predicate<V> predicate,
            Runnable runner
    ) {
        // 收集步骤
        return this;
    }

    public <V> BatchRef<T> whenValue(
            Function<T, V> getter,
            Predicate<V> predicate,
            Runnable trueRunner,
            Runnable falseRunner
    ) {
        // 收集步骤
        return this;
    }
}
```

`BatchRef` 不直接暴露实体：

```java
// 不推荐暴露
T get();
```

如果确实要调试，可以提供：

```java
T unsafeGet();
```

但业务代码不要用。

---

# 五、BatchQuery 是什么？

`BatchQuery<T>` 是一个查询描述对象。

它不是查询结果，而是描述：

```text
这个对象怎么单查
这个对象怎么批量查
这个对象的 key 是什么
这个 loaderName 是什么
```

示例：

```java
public class BatchQuery<T> {

    private final String loaderName;

    private final Object key;

    private final Function<Collection<Object>, Map<Object, T>> batchLoader;

    private final Supplier<T> fallbackLoader;

    // getter / constructor
}
```

---

# 六、QueryService 怎么写？

## 1. Relation 查询

```java
public BatchQuery<GeneralContractingProjectGroupRelation> activeRelationByWorkerProjectId(
        Long workerProjectId
) {
    return BatchQuery.of(
            "projectGcRelation.activeByWorkerProjectId",
            workerProjectId,
            ids -> getActiveRelationMapByWorkerProjectIds(castLongIds(ids)),
            () -> getActiveRelationByWorkerProjectId(workerProjectId)
    );
}
```

普通单查：

```java
public GeneralContractingProjectGroupRelation getActiveRelationByWorkerProjectId(
        Long workerProjectId
) {
    return getActiveRelationMapByWorkerProjectIds(List.of(workerProjectId))
            .get(workerProjectId);
}
```

批量查：

```java
public Map<Long, GeneralContractingProjectGroupRelation> getActiveRelationMapByWorkerProjectIds(
        Collection<Long> workerProjectIds
) {
    if (workerProjectIds == null || workerProjectIds.isEmpty()) {
        return Collections.emptyMap();
    }

    List<GeneralContractingProjectGroupRelation> list = relationMapper.selectList(
            Wrappers.<GeneralContractingProjectGroupRelation>lambdaQuery()
                    .in(
                            GeneralContractingProjectGroupRelation::getWorkerProjectId,
                            workerProjectIds
                    )
                    .eq(GeneralContractingProjectGroupRelation::getDeleted, false)
                    .eq(GeneralContractingProjectGroupRelation::getStatus, 1)
    );

    return list.stream()
            .collect(Collectors.toMap(
                    GeneralContractingProjectGroupRelation::getWorkerProjectId,
                    Function.identity(),
                    (a, b) -> a
            ));
}
```

---

## 2. User 查询，带固定参数 userId

```java
public BatchQuery<GeneralContractingProjectUser> activeUserByWorkerProjectIdAndUserId(
        Long workerProjectId,
        Long userId
) {
    return BatchQuery.of(
            "projectGcUser.activeByWorkerProjectIdAndUserId:userId=" + userId,
            workerProjectId,
            ids -> getActiveUserMapByWorkerProjectIdsAndUserId(castLongIds(ids), userId),
            () -> getActiveUserByWorkerProjectIdAndUserId(workerProjectId, userId)
    );
}
```

这里 loaderName 必须带上 `userId`，因为：

```text
同一个 workerProjectId，不同 userId 查出来的结果不同
```

---

# 七、BatchRefs 怎么执行？

核心流程：

```text
BatchRef.wrap(query)
    ↓
如果当前有 BatchScope：
    把 query.key 收集到 loaderName 分组里
    返回 BatchRef
如果当前没有 BatchScope：
    直接 fallback 单查
    返回 immediate BatchRef

BatchRef.setOut / whenPresent / whenValue
    ↓
只收集 Step，不执行

BatchRefs.flush()
    ↓
按 loaderName 分组
    每组执行一次 batchLoader(keys)
    得到 Map<key, value>
    遍历所有 BatchRef
    取 value
    回放它收集的所有 Step
```

---

# 八、BatchRefs 的伪代码

```java
public final class BatchRefs {

    public static <T> BatchRef<T> register(BatchQuery<T> query) {
        if (!BatchContextHolder.exists()) {
            T value = query.fallbackLoader().get();
            return BatchRef.immediate(value);
        }

        BatchContext context = BatchContextHolder.current();

        BatchRef<T> ref = new BatchRef<>(query.loaderName(), query.key());

        context.register(query, ref);

        return ref;
    }

    public static void flush() {
        if (!BatchContextHolder.exists()) {
            return;
        }

        BatchContextHolder.current().flush();
    }
}
```

---

# 九、BatchContext 的执行逻辑

```java
public class BatchContext {

    private final Map<String, BatchGroup> groups = new LinkedHashMap<>();

    public <T> void register(BatchQuery<T> query, BatchRef<T> ref) {
        BatchGroup group = groups.computeIfAbsent(
                query.loaderName(),
                name -> new BatchGroup(query)
        );

        group.add(query.key(), ref);
    }

    public void flush() {
        for (BatchGroup group : groups.values()) {
            group.load();
            group.replaySteps();
        }
    }
}
```

---

# 十、BatchGroup 的逻辑

```java
public class BatchGroup {

    private final BatchQuery<?> sampleQuery;

    private final Set<Object> keys = new LinkedHashSet<>();

    private final List<BatchRef<?>> refs = new ArrayList<>();

    private Map<Object, Object> loadedMap = new HashMap<>();

    public void add(Object key, BatchRef<?> ref) {
        keys.add(key);
        refs.add(ref);
    }

    public void load() {
        loadedMap = sampleQuery.batchLoader().apply(keys);
    }

    public void replaySteps() {
        for (BatchRef<?> ref : refs) {
            Object value = loadedMap.get(ref.key());
            ref.replay(value);
        }
    }
}
```

---

# 十一、BatchRef 如何收集 Step？

```java
public class BatchRef<T> {

    private final String loaderName;

    private final Object key;

    private final List<Consumer<T>> presentSteps = new ArrayList<>();

    private final List<Runnable> absentSteps = new ArrayList<>();

    private T immediateValue;

    private boolean immediate;

    public <V> BatchRef<T> setOut(
            Function<T, V> getter,
            Consumer<V> setter
    ) {
        presentSteps.add(value -> setter.accept(getter.apply(value)));
        return this;
    }

    public <V> BatchRef<T> setOutOrDefault(
            Function<T, V> getter,
            Consumer<V> setter,
            V defaultValue
    ) {
        presentSteps.add(value -> setter.accept(getter.apply(value)));
        absentSteps.add(() -> setter.accept(defaultValue));
        return this;
    }

    public <V, R> BatchRef<T> setOutMapped(
            Function<T, V> getter,
            Function<V, R> mapper,
            Consumer<R> setter
    ) {
        presentSteps.add(value -> {
            V fieldValue = getter.apply(value);
            R mappedValue = mapper.apply(fieldValue);
            setter.accept(mappedValue);
        });
        return this;
    }

    public BatchRef<T> whenPresent(Runnable runner) {
        presentSteps.add(value -> runner.run());
        return this;
    }

    public BatchRef<T> whenAbsent(Runnable runner) {
        absentSteps.add(runner);
        return this;
    }

    public <V> BatchRef<T> whenValue(
            Function<T, V> getter,
            Predicate<V> predicate,
            Runnable runner
    ) {
        presentSteps.add(value -> {
            V fieldValue = getter.apply(value);
            if (predicate.test(fieldValue)) {
                runner.run();
            }
        });
        return this;
    }

    public <V> BatchRef<T> whenValue(
            Function<T, V> getter,
            Predicate<V> predicate,
            Runnable trueRunner,
            Runnable falseRunner
    ) {
        presentSteps.add(value -> {
            V fieldValue = getter.apply(value);
            if (predicate.test(fieldValue)) {
                trueRunner.run();
            } else {
                falseRunner.run();
            }
        });
        return this;
    }

    void replay(T value) {
        if (value == null) {
            for (Runnable absentStep : absentSteps) {
                absentStep.run();
            }
            return;
        }

        for (Consumer<T> presentStep : presentSteps) {
            presentStep.accept(value);
        }
    }
}
```

这个版本的核心就是：

```text
setOut / setOutMapped / whenValue
只是往 presentSteps 里加动作
whenAbsent
只是往 absentSteps 里加动作
flush 后 replay
才真正执行
```

---

# 十二、`set` 内部 value 的用法

你说的：

```java
ref.setOut(relation::getName, newRelation::setName)
ref.set(relation::setName, "sample name")
```

可以对应成两类。

## 1. 从内部对象取值，设置到外部对象

```java
ref.setOut(
        Relation::getName,
        vo::setRelationName
);
```

## 2. 给内部对象设置值

这个要谨慎，因为内部对象是查询出来的数据对象，不一定应该被修改。

如果只是临时改内存对象，可以提供：

```java
ref.setIn(
        Relation::setName,
        "sample name"
);
```

实现：

```java
public <V> BatchRef<T> setIn(
        BiConsumer<T, V> setter,
        V value
) {
    presentSteps.add(entity -> setter.accept(entity, value));
    return this;
}
```

使用：

```java
relationRef.setIn(
        GeneralContractingProjectGroupRelation::setRemark,
        "sample name"
);
```

但我建议少用 `setIn`，因为容易让人误以为会写回数据库。

更推荐只用 `setOut`，也就是把内部值设置到外部 VO。

---

# 十三、初步使用说明

## 使用规则 1：业务方法加 `@BatchScope`

```java
@BatchScope
public List<ProjectVO> getProjectList(ProjectListParam param) {
    ...
}
```

如果没加，`BatchRef.wrap(...)` 会直接走单查 fallback。

---

## 使用规则 2：QueryService 返回 `BatchQuery<T>`

```java
projectGcRelationQueryService.activeRelationByWorkerProjectId(projectId)
```

它不是结果，只是查询描述。

---

## 使用规则 3：用 `BatchRef.wrap(...)` 包装查询描述

```java
BatchRef<Relation> relationRef =
        BatchRef.wrap(
                relationQueryService.activeRelationByWorkerProjectId(projectId)
        );
```

---

## 使用规则 4：业务代码只写步骤，不直接取实体

```java
relationRef.setOut(Relation::getId, vo::setRelationId);
relationRef.whenPresent(() -> vo.setRelated(true));
relationRef.whenAbsent(() -> vo.setRelated(false));
```

---

## 使用规则 5：最后统一 `BatchRefs.flush()`

```java
BatchRefs.flush();
```

如果不想手动写，可以后续让 AOP 在方法结束前自动 flush。
但第一版建议手动写，语义清楚。

---

# 十四、实现规划

## 第一步：先实现最小版本

只支持：

```java
BatchRef.wrap(...)
BatchRefs.flush()

ref.whenPresent(...)
ref.whenAbsent(...)
ref.setOut(...)
ref.setOutMapped(...)
ref.whenValue(...)
```

不要一开始做太多。

---

## 第二步：QueryService 手写 BatchQuery 方法

例如：

```java
activeRelationByWorkerProjectId(...)
activeUserByWorkerProjectIdAndUserId(...)
latestPendingApprovalByWorkerProjectId(...)
```

每个方法都明确：

```text
loaderName
key
batchLoader
fallbackLoader
```

复杂条件都封在这里。

---

## 第三步：列表页先试点

先选一个典型方法，比如：

```java
getProjectList(ProjectListParam param)
```

改成：

```text
for 循环里 wrap ref + 收集 step
BatchRefs.flush()
return
```

---

## 第四步：补充安全限制

建议加这些限制：

```text
1. BatchRef 不暴露 get()
2. setIn 标注为危险方法，慎用
3. flush 后不允许继续注册新 ref，或者注册后要求再次 flush
4. 没有 BatchScope 时 fallback 单查
5. loaderName 必须包含固定条件，比如 userId
6. 支持 null 缓存，避免重复查空值
```

---

## 第五步：再考虑 AOP 自动 flush

第一版手动：

```java
BatchRefs.flush();
```

第二版可以在 `@BatchScope` 的 AOP finally 里：

```text
如果还有未 flush 的 ref，自动 flush
```

但我建议仍然保留显式 `BatchRefs.flush()`，因为业务更清楚。

---

# 十五、最终效果

原来你写：

```java
Relation relation = relationService.getOne(...);

if (relation != null) {
    project.setRelatedToGc(true);
    project.setGcProjectId(relation.getGcProjectId());
} else {
    project.setRelatedToGc(false);
}
```

改成：

```java
BatchRef<Relation> relationRef =
        BatchRef.wrap(
                relationQueryService.activeRelationByWorkerProjectId(project.getProjectId())
        );

relationRef.whenPresent(() -> project.setRelatedToGc(true));

relationRef.whenAbsent(() -> project.setRelatedToGc(false));

relationRef.setOut(
        Relation::getGcProjectId,
        project::setGcProjectId
);
```

然后最后：

```java
BatchRefs.flush();
```

它就会：

```text
统一收集所有 projectId
一次批量查询 relationMap
按每个 ref 的 key 找 value
回放 whenPresent / whenAbsent / setOut / whenValue
```

一句话：

**你要的不是“代理对象直接取值”，而是“BatchRef 收集对内部值的操作步骤，最后批量查完统一回放”。**

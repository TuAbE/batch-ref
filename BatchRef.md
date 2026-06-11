# BatchRef

## 核心思想

```text
1. 业务代码里写 ref.setOut / ref.whenPresent / ref.whenValue
2. 这些方法不马上取值、不马上执行
3. 只是把“以后要做的动作”收集起来
4. @BatchScope 方法结束时，AOP 外层自动统一批量查询
5. 查询完成后，按顺序回放所有动作
6. 回放时拿到真正的值，对每个 ref 依次判断 whenPresent / whenAbsent / whenValue / setOut
```

这样业务代码就不会直接：

```java
Relation relation = relationRef.get();
```

而是写成：

```java
relationRef.setOut(project::setRelationName, Relation::getName);
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
                        projectGcRelationQueryService::getActiveRelationByWorkerProjectId,
                        project.getProjectId()
                );

        BatchRef<GeneralContractingProjectUser> gcUserRef =
                BatchRef.wrap(
                        projectGcUserQueryService::getActiveUserByWorkerProjectIdAndUserId,
                        project.getProjectId(),
                        param.getUserId()
                );

        fillGcInfo(project, relationRef, gcUserRef);
    }

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

    relationRef.whenPresent(relation -> {
        project.setRelatedToGc(relation.getId() != null);
    });

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

    gcUserRef.whenPresent(gcUser -> {
        project.setJoinedGcProject(gcUser.getId() != null);
    });

    gcUserRef.setOut(
            project::setGcUserId,
            GeneralContractingProjectUser::getId
    );

    gcUserRef.setOut(project::setGcUserType)
            .from(GeneralContractingProjectUser::getUserType);

    gcUserRef.setOut(project::setGcUserTypeName)
            .fromMapped(
                    GeneralContractingProjectUser::getUserType,
                    this::toGcUserTypeName
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
2. whenPresent 可以在回放时拿到真实对象
3. setOut 优先写 setter，再写从内部对象读取哪个值
4. 赋值和条件判断通过 Function / Consumer / Predicate 输入
5. 执行时机统一交给 `@BatchScope` AOP 自动 flush
```

---

# 三、你要的几种常用动作

## 1. 直接把内部字段设置到外部对象

```java
relationRef.setOut(
        project::setGcProjectId,
        GeneralContractingProjectGroupRelation::getGcProjectId
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
        project::setGcRelationStatusName,
        GeneralContractingProjectGroupRelation::getStatus,
        status -> Objects.equals(status, 1) ? "正常" : "已停用"
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

## 2.1 流式设置输出值

```java
relationRef.setOut(project::setGcProjectId)
        .from(GeneralContractingProjectGroupRelation::getGcProjectId);

relationRef.setOut(project::setNeedGcApproval)
        .fromMapped(
                GeneralContractingProjectGroupRelation::getNeedApproval,
                Boolean.TRUE::equals
        );
```

含义：

```text
先声明写到哪里，再声明值从哪里来。
```

---

## 3. 存在时执行

```java
relationRef.whenPresent(relation -> {
    project.setRelatedToGc(relation.getId() != null);
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
relationRef.setOut(project::setGcRelationName)
        .fromOrDefault(
                GeneralContractingProjectGroupRelation::getName,
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

# 四、初步使用说明

## 使用规则 1：业务方法加 `@BatchScope`

```java
@BatchScope
public List<ProjectVO> getProjectList(ProjectListParam param) {
    ...
}
```

如果没加，`BatchRef.wrap(...)` 会直接执行被 `@BatchQueryMethod` 标注的单查方法。
在 `@BatchScope` 内请使用 `BatchRef.wrap(service::method, args...)` 这种直接方法引用，框架会从方法引用解析 `@BatchQueryMethod` 并登记批量查询；不要写成 `BatchRef.wrap(() -> service.method(arg))`。
`whenPresent`、`whenAbsent`、`whenValue` 回调里可以继续注册新的 `BatchRef`，这些新 ref 会在当前回放结束后自动进入下一轮批量 flush。

---

## 使用规则 2：用 `BatchRef.wrap(...)` 包装查询描述

```java
BatchRef<Relation> relationRef =
        BatchRef.wrap(
                relationQueryService::getActiveRelationByWorkerProjectId,
                projectId
        );
```

QueryService 只保留单查方法。`@BatchQueryMethod.batchMethod` 显式绑定批量方法名，IDEA 插件提供当前类批量方法补全、跳转、重命名、错误标红和隐式引用识别；starter 内置注解处理器会在编译期校验方法名和批量方法签名，写错会直接终止编译。

```java
@BatchQueryMethod(batchMethod = "getActiveRelationMapByWorkerProjectIds")
public Relation getActiveRelationByWorkerProjectId(Long workerProjectId) {
    return getOneActiveRelation(workerProjectId);
}

private Map<Long, Relation> getActiveRelationMapByWorkerProjectIds(Collection<Long> workerProjectIds) {
    return getActiveRelationMap(workerProjectIds);
}
```

多参数查询继续使用强类型方法引用，不建议把主 API 做成 `Object...`：

```java
BatchRef.wrap(
        userQueryService::getActiveUserByWorkerProjectIdAndUserId,
        projectId,
        userId
);
```

如果需要保留入参名字，推荐把多参数收成一个 record：

```java
record ActiveUserQuery(Long workerProjectId, Long userId) {
}

BatchRef.wrap(userQueryService::getActiveUser, new ActiveUserQuery(projectId, userId));
```

---

## 使用规则 3：业务代码只写步骤，不直接取实体

```java
relationRef.setOut(vo::setRelationId, Relation::getId);
relationRef.whenPresent(relation -> vo.setRelated(relation.getId() != null));
relationRef.whenAbsent(() -> vo.setRelated(false));
```

---

## 使用规则 4：`@BatchScope` 自动 flush，拿到值后回放步骤

业务代码不需要手动调用 `BatchRefs.flush()`。
`@BatchScope` AOP 在方法正常返回前自动执行 flush：

```text
1. 收集所有 BatchRef 的 key，按注解方法自动生成的 loaderName 分组
2. 每组执行一次批量查询，拿到 Map<key, 真正的值>
3. 遍历每个 BatchRef，用 key 取出真正的值
4. 对每个 ref 依次判断：
   - 值存在 → 回放 whenPresent / setOut / setOutMapped / whenValue
   - 值不存在 → 回放 whenAbsent / setOutOrDefault 的默认值
```

---

# 五、实现规划

## 第一步：先实现最小版本

只支持：

```java
@BatchScope  // AOP 自动 flush

BatchRef.wrap(...)

ref.whenPresent(...)
ref.whenAbsent(...)
ref.setOut(...)
ref.setOutMapped(...)
ref.whenValue(...)
```

不要一开始做太多。

---

## 第二步：列表页先试点

先选一个典型方法，比如：

```java
getProjectList(ProjectListParam param)
```

改成：

```text
方法加 @BatchScope
for 循环里 wrap ref + 收集 step
return（AOP 自动 flush）
```

---

## 第三步：补充安全限制

建议加这些限制：

```text
1. BatchRef 不暴露 get()
2. BatchRef 不提供 setIn 这类直接操作内部实体的方法
3. flush 后不允许继续注册新 ref，或者注册后要求再次 flush
4. 没有 BatchScope 时 fallback 单查
5. 支持 null 缓存，避免重复查空值
```

---

# 六、最终效果

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
                relationQueryService::getActiveRelationByWorkerProjectId,
                project.getProjectId()
        );

relationRef.whenPresent(relation -> project.setRelatedToGc(relation.getId() != null));

relationRef.whenAbsent(() -> project.setRelatedToGc(false));

relationRef.setOut(
        project::setGcProjectId,
        Relation::getGcProjectId
);
```

`@BatchScope` AOP 在方法返回前自动 flush，它会：

```text
统一收集所有 projectId
一次批量查询 relationMap
按每个 ref 的 key 找到真正的值
对每个 ref：
  值存在 → 回放 whenPresent / setOut / whenValue
  值不存在 → 回放 whenAbsent / setOutOrDefault 的默认值
```

一句话：

**你要的不是“代理对象直接取值”，而是“BatchRef 收集对内部值的操作步骤，最后批量查完统一回放”。**

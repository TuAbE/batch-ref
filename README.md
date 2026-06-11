# BatchRef Spring Boot Starter

BatchRef 用于把循环里的单个查询描述收集起来，在 `@BatchScope` 自动 flush 时按查询方法合并成批量查询，再把 `setOut`、`whenPresent`、`whenAbsent`、`whenValue` 等步骤回放到业务对象上。

完整设计稿保留在 [BatchRef.md](BatchRef.md)，业务使用方式以其中示例为准，本实现不改变文档里的调用方式。

## 环境

- Java 17
- Spring Boot 3.5.x

## 引入

```xml
<dependency>
    <groupId>io.github.batchref</groupId>
    <artifactId>batch-ref-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Spring Boot 自动装配

starter 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动装配 `BatchScopeAspect` 和 `BatchQueryMethodAspect`。

可配置项：

```yaml
batch-ref:
  enabled: true
  auto-flush: true
  flush-on-exception: false
  aspect-order: 2147483547
```

`auto-flush=true` 时，`@BatchScope` 方法正常返回前会自动 flush。业务代码只登记填充步骤，不需要手动调用 `BatchRefs.flush()`。

## 业务代码

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

        relationRef.whenPresent(relation -> project.setRelatedToGc(relation.getId() != null));
        relationRef.whenAbsent(() -> project.setRelatedToGc(false));
        relationRef.setOut(
                project::setGcProjectId,
                GeneralContractingProjectGroupRelation::getGcProjectId
        );
        relationRef.setOut(project::setNeedGcApproval)
                .fromMapped(
                        GeneralContractingProjectGroupRelation::getNeedApproval,
                        Boolean.TRUE::equals
                );
    }

    return projectList;
}
```

没有 `@BatchScope` 时，`BatchRef.wrap(...)` 会直接执行被 `@BatchQueryMethod` 标注的单查方法，并在后续登记步骤时立即执行对应动作。

## QueryService

QueryService 只保留单查方法。`@BatchQueryMethod.batchMethod` 显式绑定批量方法名，IDEA 插件提供当前类批量方法补全、跳转、重命名、错误标红和隐式引用识别；starter 内置注解处理器会在编译期校验方法名和批量方法签名，写错会直接终止编译。loaderName 和 key 由框架根据类名、方法签名和入参自动生成；fallback 直接执行这个单查方法。

```java
@BatchQueryMethod(batchMethod = "getActiveRelationMapByWorkerProjectIds")
public Relation getActiveRelationByWorkerProjectId(Long workerProjectId) {
    return relationMapper.selectActiveByWorkerProjectId(workerProjectId);
}

private Map<Long, Relation> getActiveRelationMapByWorkerProjectIds(Collection<Long> workerProjectIds) {
    return relationMapper.selectActiveByWorkerProjectIds(workerProjectIds)
            .stream()
            .collect(Collectors.toMap(Relation::getWorkerProjectId, Function.identity()));
}
```

多参数查询可以继续使用强类型方法引用：

```java
BatchRef.wrap(
        projectGcUserQueryService::getActiveUserByWorkerProjectIdAndUserId,
        project.getProjectId(),
        param.getUserId()
);
```

如果希望在 key 上保留参数名，推荐把多个参数收成一个 record：

```java
record ActiveUserQuery(Long workerProjectId, Long userId) {
}

BatchRef.wrap(projectGcUserQueryService::getActiveUser, new ActiveUserQuery(project.getProjectId(), param.getUserId()));
```

MyBatis Plus 示例见 [docs/mybatis-plus.md](docs/mybatis-plus.md)。

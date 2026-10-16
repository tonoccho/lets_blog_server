package com.letsblog.identity.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.identity.client.OutboundAuthHeaders;
import com.letsblog.identity.client.ProjectServiceClient;
import com.letsblog.identity.client.PublishingServiceClient;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.messaging.DomainEventPublisher;
import com.letsblog.identity.repository.ProjectUserRepository;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.repository.UserSiteAuthorRepository;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.AuditLogService;
import com.letsblog.identity.service.CurrentActorService;
import com.letsblog.identity.service.PermissionAuthorizationService;
import com.letsblog.identity.service.ProjectUserSyncService;
import com.letsblog.identity.service.RoleService;
import com.letsblog.identity.service.UserMigrationPersister;
import com.letsblog.identity.service.UserService;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * identityサービスの自前のクラスを<b>実物</b>で組み立てる単体テスト用の土台(issue #1826)。
 *
 * <p>差し替えるのは外部との通信だけ。DBはSpring Dataのリポジトリ(Mockito)、MQは{@link RabbitTemplate}
 * (Mockito)、HTTPはサーバー側のフェイク({@link FakeKeycloak}と、project-service / publishing-service
 * を兼ねる{@link FakeHttpServer})。{@code CurrentActorService}・{@code AuditLogService}・
 * {@code DomainEventPublisher}・{@code UserMigrationPersister}・{@code KeycloakAdminClient}・
 * 各{@code *Client}・{@code ObjectMapper}はすべて実物。
 *
 * <p>操作者は{@link #actAs}で、実際のJWT({@link JwtAuthenticationToken})をSecurityContextへ置いて作る。
 * リクエストはSpring Testの{@link MockHttpServletRequest}(Mockitoのモックではない)。
 */
public final class IdentityWorld implements AutoCloseable {

    public final UserRepository userRepository = Mockito.mock(UserRepository.class);
    public final RoleRepository roleRepository = Mockito.mock(RoleRepository.class);
    public final ProjectUserRepository projectUserRepository = Mockito.mock(ProjectUserRepository.class);
    public final UserSiteAuthorRepository userSiteAuthorRepository = Mockito.mock(UserSiteAuthorRepository.class);
    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);

    public final MockHttpServletRequest request = new MockHttpServletRequest();
    public final FakeKeycloak keycloak = new FakeKeycloak();
    /** project-serviceとpublishing-service(内部ブリッジ)を兼ねるHTTPサーバー。 */
    public final FakeHttpServer bridges = new FakeHttpServer();
    public final ObjectMapper objectMapper = new ObjectMapper();
    public final CredentialCipher credentialCipher =
            new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));

    public final CurrentActorService currentActorService = new CurrentActorService(request, userRepository);
    public final AdminAuthorizationService adminAuthorizationService =
            new AdminAuthorizationService(currentActorService);
    public final PermissionAuthorizationService permissionAuthorizationService =
            new PermissionAuthorizationService(currentActorService, userRepository);
    public final KeycloakAdminClient keycloakAdminClient = keycloak.client();
    public final DomainEventPublisher domainEventPublisher = new DomainEventPublisher(rabbitTemplate);
    public final AuditLogService auditLogService =
            new AuditLogService(rabbitTemplate, currentActorService, objectMapper);
    public final UserMigrationPersister userMigrationPersister = new UserMigrationPersister(userRepository);
    public final UserService userService = new UserService(
            userRepository, roleRepository, credentialCipher, keycloakAdminClient, domainEventPublisher,
            auditLogService, userMigrationPersister);
    public final RoleService roleService = new RoleService(roleRepository, userRepository);

    public final ServiceTokenClient serviceTokenClient = new ServiceTokenClient(
            RestClient.builder(), keycloak.baseUrl() + FakeKeycloak.TOKEN_PATH, "letsblog-services", "test-secret");
    public final OutboundAuthHeaders outboundAuthHeaders = new OutboundAuthHeaders(request, serviceTokenClient);
    public final ProjectServiceClient projectServiceClient =
            new ProjectServiceClient(RestClient.builder(), bridges.baseUrl(), outboundAuthHeaders);
    public final PublishingServiceClient publishingServiceClient =
            new PublishingServiceClient(RestClient.builder(), bridges.baseUrl(), outboundAuthHeaders);
    public final ProjectUserSyncService projectUserSyncService = new ProjectUserSyncService(
            projectServiceClient, projectUserRepository, userRepository, publishingServiceClient,
            userSiteAuthorRepository, auditLogService);

    /**
     * 操作者を、実際のJWTでSecurityContextへ置く。{@code users}の該当行({@code keycloakSub}で引く)は
     * リポジトリの応答として登録する。
     */
    public User actAs(Long id, String role) {
        return actAs(id, role, true);
    }

    public User actAs(Long id, String role, boolean enabled) {
        User actor = new User();
        actor.setId(id);
        actor.setEmail("actor" + id + "@example.com");
        actor.setRole(role);
        actor.setEnabled(enabled);
        return actAsUser(actor);
    }

    /** 組み立て済みの{@code users}行を操作者にする(IDが無い行など、特殊な状態を作るとき)。 */
    public User actAsUser(User actor) {
        String sub = "actor-sub-" + actor.getEmail();
        actor.setKeycloakSub(sub);
        String role = actor.getRole();
        Mockito.when(userRepository.findByKeycloakSub(sub)).thenReturn(Optional.of(actor));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(JwtTestFixtures.jwt(sub, role)));
        return actor;
    }

    /**
     * 実物のコントローラをSpring MVCの実際のディスパッチ(MockMvc、Spring Testの実装)に載せる。
     * 例外は本番と同じ{@code GlobalExceptionHandler}がHTTPステータスへ変換する。
     */
    public org.springframework.test.web.servlet.MockMvc mvc(Object... controllers) {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new com.letsblog.identity.config.GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    /** 送った監査ログ(letsblog.logs exchange)。 */
    public List<AuditLogMessage> auditMessages() {
        return Mockito.mockingDetails(rabbitTemplate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("convertAndSend"))
                .filter(i -> LogExchanges.LOG_EXCHANGE.equals(i.getArgument(0)))
                .map(i -> (AuditLogMessage) i.getArgument(2))
                .toList();
    }

    /** 送ったドメインイベント(letsblog.events exchange)のペイロード。 */
    public List<Object> domainEvents() {
        return Mockito.mockingDetails(rabbitTemplate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("convertAndSend"))
                .filter(i -> EventExchanges.EVENTS_EXCHANGE.equals(i.getArgument(0)))
                .map(i -> i.getArgument(2))
                .toList();
    }

    @Override
    public void close() {
        SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        keycloak.close();
        bridges.close();
    }
}

package com.chatbot.agent.security;

import com.chatbot.agent.model.ToolModel.SideEffect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The identity on whose authority a tool invocation happens.
 *
 * <p>This is passed explicitly down every call path rather than read from a thread-local
 * {@code SecurityContextHolder}. That is a deliberate choice: an authority check that silently reads
 * ambient state is one thread-hand-off away from checking the wrong principal - or none at all - and
 * the failure is invisible. Making the principal an argument means a call path that forgot to carry
 * it does not compile.
 *
 * <p>{@link #system()} exists for internal, non-user-initiated work and carries <em>no</em>
 * authority. It cannot invoke anything. Previously these call sites passed the string
 * {@code "system"} as a user id, which read like an identity but conveyed no permissions and was
 * never checked.
 */
public final class InvocationPrincipal {

    private final String name;
    private final Set<String> roles;
    private final Set<SideEffect> permittedSideEffects;
    private final boolean authenticated;

    private InvocationPrincipal(String name, Set<String> roles, boolean authenticated) {
        this.name = name;
        this.roles = Set.copyOf(roles);
        this.authenticated = authenticated;

        Set<SideEffect> permitted = EnumSet.noneOf(SideEffect.class);
        for (String role : roles) {
            permitted.addAll(Roles.permittedSideEffects(role));
        }
        this.permittedSideEffects = Set.copyOf(permitted);
    }

    /**
     * Build from a Spring {@link Authentication}. An unauthenticated or anonymous authentication
     * yields {@link #anonymous()}, which can invoke nothing.
     */
    public static InvocationPrincipal from(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            return anonymous();
        }

        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            roles.add(authority.getAuthority());
        }
        return new InvocationPrincipal(authentication.getName(), roles, true);
    }

    /** No identity, no authority. */
    public static InvocationPrincipal anonymous() {
        return new InvocationPrincipal("anonymous", Set.of(), false);
    }

    /**
     * Internal machinery with no delegated user authority. Cannot invoke any tool.
     * Use only where no user initiated the work; never as a substitute for a real principal.
     */
    public static InvocationPrincipal system() {
        return new InvocationPrincipal("system", Set.of(), false);
    }

    /** Test/bootstrap helper - explicit roles, no Spring context required. */
    public static InvocationPrincipal of(String name, String... roles) {
        return new InvocationPrincipal(name, Set.of(roles), true);
    }

    public String getName() {
        return name;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean mayInvoke(SideEffect sideEffect) {
        return permittedSideEffects.contains(sideEffect);
    }

    public Set<SideEffect> getPermittedSideEffects() {
        return permittedSideEffects;
    }

    @Override
    public String toString() {
        return "InvocationPrincipal{name=" + name + ", roles=" + roles + "}";
    }
}

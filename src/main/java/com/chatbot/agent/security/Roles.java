package com.chatbot.agent.security;

import com.chatbot.agent.model.ToolModel.SideEffect;

import java.util.EnumSet;
import java.util.Set;

/**
 * Role definitions and the side-effect budget each role carries.
 *
 * <p>Authority is expressed once, here, so that the HTTP layer and the runtime policy cannot drift
 * apart. {@code SecurityConfig} decides who may reach an endpoint; {@code ToolInvocationPolicy}
 * decides what they may do once inside. Both read these constants.
 */
public final class Roles {

    public static final String USER = "USER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";

    /** Spring stores authorities with a {@code ROLE_} prefix. */
    public static final String ROLE_USER = "ROLE_" + USER;
    public static final String ROLE_OPERATOR = "ROLE_" + OPERATOR;
    public static final String ROLE_ADMIN = "ROLE_" + ADMIN;

    private Roles() {
    }

    /**
     * The maximum side-effect class a role may invoke.
     *
     * <p>Deliberately restrictive: {@code PRIVILEGED} covers Python and JavaScript tools, which run
     * code and can call other tools, so their blast radius is not statically bounded. Only ADMIN
     * gets that, and only OPERATOR and above may cause writes at all.
     */
    public static Set<SideEffect> permittedSideEffects(String role) {
        return switch (role) {
            case ROLE_ADMIN -> EnumSet.allOf(SideEffect.class);
            case ROLE_OPERATOR -> EnumSet.of(SideEffect.READ_ONLY, SideEffect.REVERSIBLE_WRITE);
            case ROLE_USER -> EnumSet.of(SideEffect.READ_ONLY);
            default -> EnumSet.noneOf(SideEffect.class);
        };
    }
}

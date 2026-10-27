package com.school.party.domain.community.security;
import org.springframework.security.core.userdetails.UserDetails;
/** Implement this principal in the selected school-authentication adapter. Never populate its ID from a request body/header. */
public interface CampusPrincipal extends UserDetails { long userId(); }

package com.school.party.domain.community.demo;

import com.school.party.domain.user.entity.LocalCredential;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserRepository;
import com.school.party.domain.community.domain.Post;
import com.school.party.domain.community.repository.PostRepository;
import com.school.party.domain.community.security.CampusUser;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Demo profile only: throwaway H2 accounts for local checks. Each account works with HTTP Basic and,
 * through its LOCAL_CREDENTIAL row, with the real session login (POST /api/auth/local/login with the
 * student number demo-{name} or the school email demo-{name}@syuin.ac.kr, which the frontend login
 * form accepts), so the frontend can be tested end to end against this server.
 */
@Configuration @Profile("demo")
public class DemoConfiguration {
    @Bean UserDetailsService demoUsers(UserRepository users, LocalCredentialRepository credentials, PostRepository posts, Clock clock) {
        Instant now = clock.instant();
        Map<String, CampusUser> accounts = new HashMap<>();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User author = null;
        for (String name : List.of("author", "student1", "student2", "student3", "admin")) {
            Role role = name.equals("admin") ? Role.ADMIN : Role.STUDENT;
            User user = users.save(User.builder().studentNo("demo-" + name).schoolEmail("demo-" + name + "@syuin.ac.kr")
                .name(name).role(role).status(UserStatus.ACTIVE).build());
            String hash = encoder.encode("demo-pass");
            credentials.save(LocalCredential.builder().user(user).passwordHash(hash).build());
            accounts.put(name, new CampusUser(user.getId(), name, hash, role));
            if (name.equals("author")) author = user;
        }
        posts.save(Post.taxi(author, 4, now.plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES), now));
        posts.save(Post.delivery(author, 10, now.plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES), now));
        return username -> {
            CampusUser user = accounts.get(username);
            if (user == null) throw new UsernameNotFoundException("Unknown demo account");
            return user;
        };
    }
}

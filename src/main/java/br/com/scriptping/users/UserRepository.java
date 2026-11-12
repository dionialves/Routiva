package br.com.scriptping.users;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    List<AppUser> findAllByOrderByNameAsc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u order by u.id")
    List<AppUser> lockUsers();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(Long id);
}

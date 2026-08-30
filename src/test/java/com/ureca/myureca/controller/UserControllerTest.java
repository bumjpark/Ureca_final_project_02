package com.ureca.myureca.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    private User user(long id, String name, String email) {
        User u = new User(email, name);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    void 검색어가_없으면_LIKE_검색_없이_PK순_기본_목록을_반환한다() throws Exception {
        Page<User> page = new PageImpl<>(List.of(user(1L, "홍길동", "hong@test.com")), PageRequest.of(0, 20), 1);
        when(userRepository.findAll(PageRequest.of(0, 20))).thenReturn(page);

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(userRepository, never())
                .findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(anyString(), anyString(), any());
    }

    @Test
    void 검색어가_있으면_이름_이메일_마스킹된_결과를_반환한다() throws Exception {
        Page<User> page = new PageImpl<>(List.of(user(1L, "홍길동", "hong@test.com")), PageRequest.of(0, 20), 1);
        when(userRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                "hong", "hong", PageRequest.of(0, 20))).thenReturn(page);

        mockMvc.perform(get("/api/users").param("search", "hong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(1))
                .andExpect(jsonPath("$.content[0].name").isNotEmpty())
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}

package com.chronic.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.common.exception.BusinessException;
import com.chronic.points.entity.SignInRecord;
import com.chronic.points.entity.UserPoints;
import com.chronic.points.mapper.SignInRecordMapper;
import com.chronic.points.mapper.UserPointsMapper;
import com.chronic.points.vo.WeekSignInVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignInServiceImplTest {

    @Mock
    private SignInRecordMapper signInRecordMapper;

    @Mock
    private UserPointsMapper userPointsMapper;

    @Mock
    private UserPointsServiceImpl userPointsService;

    private SignInServiceImpl signInService;

    @BeforeEach
    void setUp() {
        signInService = new SignInServiceImpl(userPointsService);
        ReflectionTestUtils.setField(signInService, "baseMapper", signInRecordMapper);
    }

    @Test
    void signIn_shouldCreateRecordAndAddPoints() {
        Long userId = 1L;

        when(signInRecordMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);
        when(signInRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(signInRecordMapper.insert(any(SignInRecord.class))).thenReturn(1);
        when(userPointsService.addPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString())).thenReturn(true);

        SignInRecord record = signInService.signIn(userId);

        assertNotNull(record);
        assertEquals(userId, record.getUserId());
        assertEquals(LocalDate.now(), record.getSignDate());
        assertTrue(record.getPoints() > 0);
        verify(signInRecordMapper, times(1)).insert(any(SignInRecord.class));
        verify(userPointsService, times(1)).addPoints(anyLong(), anyInt(), eq("SIGN_IN"), nullable(Long.class), anyString());
    }

    @Test
    void signIn_shouldThrow_whenAlreadySignedToday() {
        Long userId = 1L;
        SignInRecord existing = new SignInRecord();
        existing.setUserId(userId);
        existing.setSignDate(LocalDate.now());

        when(signInRecordMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class, () -> signInService.signIn(userId));
        assertEquals("今日已领取，请明天再来", ex.getMessage());
    }

    @Test
    void getWeekSignIn_shouldReturnWeekData() {
        Long userId = 1L;

        List<SignInRecord> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SignInRecord r = new SignInRecord();
            r.setUserId(userId);
            r.setSignDate(LocalDate.now().minusDays(i));
            records.add(r);
        }
        when(signInRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(records);

        WeekSignInVO vo = signInService.getWeekSignIn(userId);

        assertNotNull(vo);
        assertNotNull(vo.getWeekStart());
        assertNotNull(vo.getWeekEnd());
        assertEquals(3, vo.getSignedDays());
        assertNotNull(vo.getDays());
        assertEquals(7, vo.getDays().size());
    }

    @Test
    void getWeekSignIn_shouldReturnEmptyDays_whenNoSignIn() {
        Long userId = 1L;
        when(signInRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        WeekSignInVO vo = signInService.getWeekSignIn(userId);

        assertEquals(0, vo.getSignedDays());
        assertFalse(vo.getFullAttendance());
    }
}

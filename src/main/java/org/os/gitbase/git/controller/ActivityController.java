package org.os.gitbase.git.controller;

import lombok.RequiredArgsConstructor;
import org.os.gitbase.git.entity.Activity;
import org.os.gitbase.git.service.ActivityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

import static org.os.gitbase.constant.Constant.ACTIVITIES_MAPPING_REQUEST;
import static org.os.gitbase.constant.Constant.RECENT_ACTIVITIES;

@RestController
@RequestMapping(ACTIVITIES_MAPPING_REQUEST)
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping(RECENT_ACTIVITIES)
    public ResponseEntity<List<Activity>> getRecentActivities(Principal principal) {
        return ResponseEntity.ok(activityService.getRecentActivities(principal.getName()));
    }
}
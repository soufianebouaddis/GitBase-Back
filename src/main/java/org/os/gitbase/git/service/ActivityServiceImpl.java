package org.os.gitbase.git.service;

import lombok.RequiredArgsConstructor;
import org.os.gitbase.git.entity.Activity;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.repository.ActivityRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final ActivityRepository activityRepository;

    @Override
    public void logActivity(ActivityType type, String actor, String target, String description) {
        Activity activity = new Activity();
        activity.setType(type);
        activity.setActor(actor);
        activity.setTarget(target);
        activity.setDescription(description);
        activity.setTimestamp(LocalDateTime.now());
        activityRepository.save(activity);
    }

    @Override
    public List<Activity> getRecentActivities() {
        return activityRepository.findTop3ByOrderByTimestampDesc();
    }
}

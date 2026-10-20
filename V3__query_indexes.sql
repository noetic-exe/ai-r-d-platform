CREATE INDEX ix_memberships_user ON memberships(user_id,project_id);
CREATE INDEX ix_job_events_job ON job_events(job_id,id);
CREATE INDEX ix_experiments_project ON experiments(project_id);
CREATE INDEX ix_comments_project ON comments(project_id,id);
CREATE INDEX ix_comments_experiment ON comments(experiment_id,id);
CREATE INDEX ix_requests_user ON collaboration_requests(requester_id,user_id);

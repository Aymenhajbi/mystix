-- Quota per lane (Lot 4): new work is refused with 429 once a lane holds this many unfinished jobs (READY or RUNNING),
-- so one client's burst cannot grow the queue without bound. NULL = the default of mystix.queue.default-max-queued.
ALTER TABLE queue_lane ADD COLUMN max_queued INT;
ALTER TABLE queue_lane ADD CONSTRAINT ck_queue_lane_max_queued CHECK (max_queued IS NULL OR max_queued > 0);

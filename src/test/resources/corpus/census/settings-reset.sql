-- from settings/reset/reset_threads.test:6
select current_setting('threads');

-- from settings/reset/reset_threads.test:11
pragma threads=42;

-- from settings/reset/reset_threads.test:14
RESET threads;

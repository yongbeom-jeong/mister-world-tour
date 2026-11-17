-- 설계 9.1 SMSMessage 상태 이름(Failure)에 맞춘다
UPDATE sms_log SET status = 'FAILURE' WHERE status = 'FAILED';

-- 설계 상태도 11.1: SMSMessage 상태는 Pending/Success/Failure/Unknown 4가지. 예전 '정보 누락' 기록은 실패로 남긴다
UPDATE sms_log SET status = 'FAILURE' WHERE status = 'INVALID';

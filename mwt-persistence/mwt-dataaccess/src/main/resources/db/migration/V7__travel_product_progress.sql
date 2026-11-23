-- 직원이 처리하는 여행 출발·완료 시각 (2026-11-23). 출발 처리 때 기념품을 차감한다.
ALTER TABLE travel_product ADD COLUMN departed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE travel_product ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;

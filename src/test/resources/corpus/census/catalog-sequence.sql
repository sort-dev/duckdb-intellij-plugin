-- from catalog/sequence/sequence_cycle.test:5
create sequence minseq INCREMENT BY -1 MINVALUE -5 MAXVALUE 5 CYCLE;

-- from catalog/sequence/sequence_cycle.test:8
SELECT nextval('minseq') from generate_series(0,20);

-- from catalog/sequence/sequence_offset_increment.test:5
create sequence xx start 100 increment by 2;

-- from catalog/sequence/sequence_offset_increment.test:8
SELECT nextval('xx');

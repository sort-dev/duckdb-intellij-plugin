-- from catalog/sequence/sequence_cycle.test:5
create sequence minseq INCREMENT BY -1 MINVALUE -5 MAXVALUE 5 CYCLE;

-- from catalog/sequence/sequence_cycle.test:8
SELECT nextval('minseq') from generate_series(0,20);

-- from catalog/sequence/sequence_overflow.test:5
create sequence seq1 INCREMENT BY 1 MINVALUE 9223372036854775800 MAXVALUE 9223372036854775807 CYCLE;

-- from catalog/sequence/sequence_overflow.test:8
SELECT nextval('seq1') from generate_series(0,20);

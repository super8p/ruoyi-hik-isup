-- �����������ݿ��е�����Ʒ��/�������
-- �������ѳ�ʼ������ ry-vue ���ݿ�

-- ɾ�����������˵�����ɫ����
DELETE FROM sys_role_menu WHERE menu_id = 4;
DELETE FROM sys_menu WHERE menu_id = 4;

-- ���²���ʾ������
UPDATE sys_dept SET dept_name = 'Hik ISUP', leader = '������', email = 'admin@example.com' WHERE dept_id = 100;
UPDATE sys_dept SET leader = '������', email = 'admin@example.com' WHERE dept_id BETWEEN 101 AND 109;

-- �����û��ǳ�
UPDATE sys_user SET nick_name = '����Ա', email = 'admin@example.com' WHERE user_id = 1;
UPDATE sys_user SET nick_name = '����Ա', email = 'test@example.com' WHERE user_id = 2;

-- �滻������ع���
UPDATE sys_notice SET notice_title = 'ϵͳ����֪ͨ', notice_content = 'Hik ISUP �����Ѳ�����ͨ��������̨�����豸���������á�' WHERE notice_id = 1;
UPDATE sys_notice SET notice_title = 'ϵͳά��֪ͨ', notice_content = 'ϵͳ����ά�������ڽ�������������ǰ����������á�' WHERE notice_id = 2;
UPDATE sys_notice SET notice_title = 'ƽ̨����˵��', notice_content = '<p>Hik ISUP ƽ̨֧���豸ע�ᡢʵʱԤ����¼��طš��������͡������Խ�����̨���Ƶȹ��ܡ�</p>' WHERE notice_id = 3;

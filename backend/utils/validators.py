# -*- coding: utf-8 -*-

"""
@Remark: 数据验证工具模块
"""
import re
from typing import Tuple, Optional

# 手机号验证正则
REGEX_MOBILE = r"^1[356789]\d{9}$|^147\d{8}$|^176\d{8}$"

# 身份证正则
IDCARD_REGEX = r"^[1-9]\d{5}(18|19|20|(3\d))\d{2}((0[1-9])|(1[0-2]))(([0-2][1-9])|10|20|30|31)\d{3}[0-9Xx]$"

# 密码验证正则：必须包含字母和数字，长度为6-20个字符
PASSWORD_REGEX = r'^(?=.*[0-9])(?=.*[a-zA-Z]).{6,20}$'


def is_valid_email(email: str) -> bool:
    """
    验证邮箱格式
    
    Args:
        email: 待验证的邮箱地址
        
    Returns:
        bool: 是否有效
        
    Examples:
        >>> is_valid_email("test@example.com")
        True
        >>> is_valid_email("invalid-email")
        False
    """
    if not email:
        return False
    
    pattern = r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$'
    return bool(re.fullmatch(pattern, email))


def check_phonenum(phonenum: str) -> bool:
    """
    验证手机号码格式
    
    Args:
        phonenum: 待验证的手机号码
        
    Returns:
        bool: 是否有效
        
    Examples:
        >>> check_phonenum("13812345678")
        True
        >>> check_phonenum("12345678901")
        False
    """
    if not phonenum:
        return False
    
    mobile_pat = re.compile(r'^1([38]\d|5[0-35-9]|7[3678])\d{8}$')
    return bool(re.search(mobile_pat, phonenum))


def is_valid_idcard(idcard: str) -> bool:
    """
    验证身份证号码格式
    
    Args:
        idcard: 待验证的身份证号码
        
    Returns:
        bool: 是否有效
        
    Examples:
        >>> is_valid_idcard("110101199003073434")
        True
        >>> is_valid_idcard("123456789012345678")
        False
    """
    if not idcard:
        return False
    
    return bool(re.match(IDCARD_REGEX, idcard))


def is_valid_password(password: str) -> bool:
    """
    验证密码强度（简化版）
    
    Args:
        password: 待验证的密码
        
    Returns:
        bool: 是否有效
        
    Examples:
        >>> is_valid_password("abc123")
        True
        >>> is_valid_password("123456")
        False
    """
    if not password:
        return False
    
    return bool(re.match(PASSWORD_REGEX, password))


def validate_password_strength(password: str) -> Tuple[bool, str]:
    """
    验证密码强度（详细版）
    
    规则：
    - 必须包含字母和数字
    - 长度为6-20个字符
    - 不能为空
    
    Args:
        password: 待验证的密码
        
    Returns:
        tuple: (is_valid, error_message)
            - is_valid: 是否有效
            - error_message: 验证失败时的错误信息，成功时为空字符串
            
    Examples:
        >>> is_valid, msg = validate_password_strength("abc123")
        >>> print(is_valid, msg)
        (True, '')
        
        >>> is_valid, msg = validate_password_strength("123456")
        >>> print(is_valid, msg)
        (False, '密码必须包含字母和数字，且长度为6-20个字符')
        
        >>> is_valid, msg = validate_password_strength("")
        >>> print(is_valid, msg)
        (False, '密码不能为空')
    """
    if not password:
        return (False, "密码不能为空")
    
    if len(password) < 6 or len(password) > 20:
        return (False, "密码长度必须为6-20个字符")
    
    if not re.search(r'[a-zA-Z]', password):
        return (False, "密码必须包含字母")
    
    if not re.search(r'[0-9]', password):
        return (False, "密码必须包含数字")
    
    return (True, "")


def validate_password_confirm(password: str, confirm_password: str) -> Tuple[bool, str]:
    """
    验证两次密码输入是否一致
    
    Args:
        password: 第一次输入的密码
        confirm_password: 第二次输入的密码
        
    Returns:
        tuple: (is_valid, error_message)
            
    Examples:
        >>> is_valid, msg = validate_password_confirm("abc123", "abc123")
        >>> print(is_valid, msg)
        (True, '')
        
        >>> is_valid, msg = validate_password_confirm("abc123", "abc456")
        >>> print(is_valid, msg)
        (False, '两次密码不一致')
    """
    if not password:
        return (False, "密码不能为空")
    
    if not confirm_password:
        return (False, "确认密码不能为空")
    
    if password != confirm_password:
        return (False, "两次密码不一致")
    
    return (True, "")


def validate_new_password(
    new_password: str,
    confirm_password: str,
    check_strength: bool = True
) -> Tuple[bool, str]:
    """
    验证新密码（组合验证：一致性 + 强度）
    
    Args:
        new_password: 新密码
        confirm_password: 确认密码
        check_strength: 是否检查密码强度，默认为 True
        
    Returns:
        tuple: (is_valid, error_message)
            
    Examples:
        >>> is_valid, msg = validate_new_password("abc123", "abc123")
        >>> print(is_valid, msg)
        (True, '')
        
        >>> is_valid, msg = validate_new_password("123456", "123456")
        >>> print(is_valid, msg)
        (False, '密码必须包含字母和数字，且长度为6-20个字符')
    """
    # 验证两次密码一致
    is_valid, message = validate_password_confirm(new_password, confirm_password)
    if not is_valid:
        return (False, message)
    
    # 验证密码强度
    if check_strength:
        is_valid, message = validate_password_strength(new_password)
        if not is_valid:
            return (False, message)
    
    return (True, "")


# 向后兼容的别名
checkphonenum = check_phonenum  # 兼容旧代码

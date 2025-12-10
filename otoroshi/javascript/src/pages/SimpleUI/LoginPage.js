import React, { useState } from 'react';
import {
  Box, Card, CardContent, TextField, Button, Typography,
  Alert, CircularProgress, Container, InputAdornment, IconButton
} from '@mui/material';
import LoginIcon from '@mui/icons-material/Login';
import VisibilityIcon from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import PersonIcon from '@mui/icons-material/Person';
import LockIcon from '@mui/icons-material/Lock';

export function LoginPage({ onLoginSuccess, otoroshiLogo }) {
  const [credentials, setCredentials] = useState({ username: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [showPassword, setShowPassword] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    try {
      const response = await fetch('/bo/simple/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(credentials),
      });

      if (response.ok) {
        onLoginSuccess();
      } else {
        const contentType = response.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) {
          const data = await response.json();
          setError(data.error || 'Invalid credentials');
        } else {
          setError('Invalid credentials');
        }
      }
    } catch (err) {
      setError('Connection error. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Container maxWidth="sm">
      <Box sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%)',
      }}>
        <Card elevation={8} sx={{
          width: '100%',
          maxWidth: 450,
          borderRadius: 3,
          overflow: 'hidden'
        }} className="fade-in">
          <Box sx={{
            background: 'linear-gradient(135deg, #f9b000 0%, #ff9500 100%)',
            py: 4,
            textAlign: 'center'
          }}>
            {otoroshiLogo && (
              <img
                src={otoroshiLogo}
                alt="Otoroshi"
                style={{
                  height: 60,
                  marginBottom: 16,
                  filter: 'brightness(0) invert(1)'
                }}
              />
            )}
            <Typography variant="h4" sx={{
              color: '#000',
              fontWeight: 700,
              textShadow: '0 2px 4px rgba(0,0,0,0.1)'
            }}>
              Welcome to Otoroshi
            </Typography>
            <Typography variant="body1" sx={{ color: '#000', mt: 1, opacity: 0.9 }}>
              Simple UI
            </Typography>
          </Box>

          <CardContent sx={{ p: 4 }}>
            <Typography variant="h6" gutterBottom sx={{ mb: 3, textAlign: 'center' }}>
              Sign in to continue
            </Typography>

            {error && (
              <Alert severity="error" sx={{ mb: 3 }} onClose={() => setError(null)}>
                {error}
              </Alert>
            )}

            <Box component="form" onSubmit={handleSubmit}>
              <TextField
                fullWidth
                label="Username"
                variant="outlined"
                margin="normal"
                value={credentials.username}
                onChange={(e) => setCredentials({ ...credentials, username: e.target.value })}
                disabled={loading}
                autoFocus
                required
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <PersonIcon color="action" />
                    </InputAdornment>
                  ),
                }}
                sx={{ mb: 2 }}
              />

              <TextField
                fullWidth
                label="Password"
                type={showPassword ? 'text' : 'password'}
                variant="outlined"
                margin="normal"
                value={credentials.password}
                onChange={(e) => setCredentials({ ...credentials, password: e.target.value })}
                disabled={loading}
                required
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <LockIcon color="action" />
                    </InputAdornment>
                  ),
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton
                        aria-label="toggle password visibility"
                        onClick={() => setShowPassword(!showPassword)}
                        edge="end"
                      >
                        {showPassword ? <VisibilityOffIcon /> : <VisibilityIcon />}
                      </IconButton>
                    </InputAdornment>
                  ),
                }}
                sx={{ mb: 3 }}
              />

              <Button
                fullWidth
                type="submit"
                variant="contained"
                size="large"
                disabled={loading || !credentials.username || !credentials.password}
                startIcon={loading ? <CircularProgress size={20} color="inherit" /> : <LoginIcon />}
                sx={{
                  py: 1.5,
                  textTransform: 'none',
                  fontSize: '1.1rem',
                  fontWeight: 600,
                  boxShadow: 3,
                  '&:hover': {
                    boxShadow: 6
                  }
                }}
              >
                {loading ? 'Signing in...' : 'Sign In'}
              </Button>
            </Box>
          </CardContent>

          <Box sx={{
            p: 2,
            textAlign: 'center',
            bgcolor: '#f5f5f5',
            borderTop: '1px solid #e0e0e0'
          }}>
            <Typography variant="caption" color="text.secondary">
              Otoroshi Simple UI - Simplified gateway management
            </Typography>
          </Box>
        </Card>
      </Box>
    </Container>
  );
}
